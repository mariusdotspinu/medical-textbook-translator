package com.medlearn.translation.application;

import com.medlearn.platform.config.TranslationProperties;
import com.medlearn.ingestion.domain.PageLayout;
import com.medlearn.ingestion.domain.TextBlock;
import com.medlearn.ingestion.domain.port.PageLayoutRepository;
import com.medlearn.jobs.application.JobCoordinator;
import com.medlearn.jobs.domain.Artifact;
import com.medlearn.jobs.domain.ArtifactKind;
import com.medlearn.jobs.domain.JobSpec;
import com.medlearn.jobs.domain.JobType;
import com.medlearn.jobs.domain.port.ArtifactRepository;
import com.medlearn.learning.application.GenerateStudyGuideUseCase;
import com.medlearn.library.domain.Chapter;
import com.medlearn.library.domain.Textbook;
import com.medlearn.library.domain.port.ChapterRepository;
import com.medlearn.library.domain.port.TextbookRepository;
import com.medlearn.shared.ChapterId;
import com.medlearn.shared.ContentHash;
import com.medlearn.shared.DomainError;
import com.medlearn.shared.JobId;
import com.medlearn.shared.LanguageTag;
import com.medlearn.shared.PageRange;
import com.medlearn.shared.TextbookId;
import com.medlearn.shared.port.BlobStorePort;
import com.medlearn.translation.domain.BatchPlanner;
import com.medlearn.translation.domain.GlossaryMatcher;
import com.medlearn.translation.domain.GlossaryTerm;
import com.medlearn.translation.domain.QualityFinding;
import com.medlearn.translation.domain.RenderMode;
import com.medlearn.translation.domain.TranslatedBlock;
import com.medlearn.translation.domain.port.PdfRendererPort;
import com.medlearn.translation.domain.port.TranslationMemoryPort;
import com.medlearn.translation.domain.port.TranslationModelPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Translates a chapter range and renders it back to a PDF.
 *
 * <p>The shape of this is driven by three facts. Translating a chapter takes minutes, so it
 * is a job rather than a request. Most of that time is spent waiting on HTTP, so the work
 * runs on virtual threads with a semaphore sized to the API's rate limit rather than to the
 * machine. And every translated block is cached by content, so a retried or overlapping run
 * is nearly free.
 */
@Service
public class TranslateChaptersUseCase {

    private static final Logger log = LoggerFactory.getLogger(TranslateChaptersUseCase.class);

    private final TextbookRepository textbooks;
    private final ChapterRepository chapters;
    private final PageLayoutRepository pageLayouts;
    private final BlobStorePort blobStore;
    private final TranslationMemoryPort memory;
    private final ArtifactRepository artifacts;
    private final JobCoordinator jobs;
    private final TranslationQualityGate qualityGate;
    private final List<PdfRendererPort> renderers;
    private final List<TranslationModelPort> models;
    private final GenerateStudyGuideUseCase studyGuides;
    private final TranslationProperties properties;
    private final GlossaryService glossary;
    private final ApiSlots apiSlots;

    public TranslateChaptersUseCase(
            TextbookRepository textbooks,
            ChapterRepository chapters,
            PageLayoutRepository pageLayouts,
            BlobStorePort blobStore,
            TranslationMemoryPort memory,
            ArtifactRepository artifacts,
            JobCoordinator jobs,
            TranslationQualityGate qualityGate,
            List<PdfRendererPort> renderers,
            List<TranslationModelPort> models,
            GenerateStudyGuideUseCase studyGuides,
            TranslationProperties properties,
            GlossaryService glossary,
            ApiSlots apiSlots) {
        this.textbooks = textbooks;
        this.chapters = chapters;
        this.pageLayouts = pageLayouts;
        this.blobStore = blobStore;
        this.memory = memory;
        this.artifacts = artifacts;
        this.jobs = jobs;
        this.qualityGate = qualityGate;
        this.renderers = renderers;
        this.models = models;
        this.studyGuides = studyGuides;
        this.properties = properties;
        this.glossary = glossary;
        this.apiSlots = apiSlots;
    }

    public Outcome submit(Command command) {
        Optional<Textbook> textbook = textbooks.findById(command.textbookId());
        if (textbook.isEmpty()) {
            return new Outcome.Rejected(new DomainError.TextbookNotFound(command.textbookId()));
        }
        if (!textbook.get().readyForTranslation()) {
            return new Outcome.Rejected(new DomainError.TextbookNotReady(
                    command.textbookId(), textbook.get().status().name()));
        }

        List<Chapter> selected = resolveChapters(command);
        if (selected.isEmpty()) {
            return new Outcome.Rejected(
                    new DomainError.ChapterNotFound(command.chapterIds().isEmpty()
                            ? ChapterId.newId() : command.chapterIds().getFirst()));
        }

        JobSpec spec = new JobSpec(
                JobType.TRANSLATE, command.textbookId(), Optional.of(command.targetLanguage()),
                selected.stream().map(Chapter::id).toList(),
                Map.of("renderMode", command.renderMode().name(),
                        "includeStudyGuide", String.valueOf(command.includeStudyGuide()),
                        "dryRun", String.valueOf(command.dryRun())));
        JobId jobId = jobs.submit(spec, "preparing",
                progress -> run(command, textbook.get(), selected, progress));

        return new Outcome.Accepted(jobId);
    }

    void run(Command command, Textbook textbook, List<Chapter> selected,
             JobCoordinator.ProgressSink progress) {

        PageRange span = selected.stream().map(Chapter::range).reduce(PageRange::union).orElseThrow();
        List<PageLayout> pages = pageLayouts.findRange(textbook.id(), span);

        List<TextBlock> blocks = pages.stream()
                .flatMap(page -> page.translatableBlocks().stream())
                .toList();

        if (blocks.isEmpty()) {
            throw new IllegalStateException(
                    "No translatable text found in " + span + " - the pages may be scans");
        }

        Map<String, Integer> pageByBlock = new HashMap<>();
        for (PageLayout page : pages) {
            for (TextBlock block : page.translatableBlocks()) {
                pageByBlock.put(block.id(), page.pageNo());
            }
        }

        TranslationModelPort model = selectModel(command.dryRun());
        progress.total(blocks.size());

        // Live runs grow the glossary first, so this run already uses the new terms. Dry runs
        // only read it: a fake rendering stored in the glossary would reach every live run.
        if (model.billable()) {
            progress.phase("building glossary");
            glossary.extend(textbook.id(), command.targetLanguage(), pages, progress);
            if (progress.cancelled()) return;
        }
        GlossaryMatcher matcher = GlossaryMatcher.of(
                glossary.termsFor(textbook.id(), command.targetLanguage()));

        log.info("Translating {} blocks over {} using {} into {}",
                blocks.size(), span, model.name(), command.targetLanguage());

        Map<String, String> translations =
                translateAll(blocks, command, model, progress, pageByBlock, matcher);

        // Checked between stages: a cancelled job must not go on to produce artifacts.
        if (progress.cancelled()) {
            log.info("Translation job {} cancelled before rendering", progress.jobId());
            return;
        }

        progress.phase("rendering");
        PdfRendererPort renderer = selectRenderer(command.renderMode());
        Path sourcePdf = blobStore.resolveToLocalFile(textbook.blob());

        PdfRendererPort.RenderResult rendered = renderer.render(new PdfRendererPort.RenderRequest(
                sourcePdf, pages, translations, command.targetLanguage(),
                "%s - %s".formatted(textbook.title(), describeSelection(selected))));

        String filename = "%s-%s-%s.pdf".formatted(
                slug(textbook.title()), slug(describeSelection(selected)),
                command.targetLanguage().tag());
        store(progress.jobId(), ArtifactKind.TRANSLATION, rendered.pdf(), filename,
                rendered.pageCount());
        report(progress, rendered.findings(), pageByBlock);
        log.info("Rendered {} ({} pages) with {} finding(s)",
                filename, rendered.pageCount(), rendered.findings().size());

        if (command.includeStudyGuide() && !progress.cancelled()) {
            progress.phase("writing study guide");
            generateStudyGuide(command, textbook, selected, pages, translations, matcher, progress);
        }
    }

    /**
     * Builds the study guide from the <em>translated</em> text, not the source.
     *
     * <p>That way the guide uses exactly the terminology the reader just saw in the
     * translated chapter, instead of independently inventing a second rendering of every term.
     */
    private void generateStudyGuide(
            Command command, Textbook textbook, List<Chapter> selected,
            List<PageLayout> pages, Map<String, String> translations,
            GlossaryMatcher matcher, JobCoordinator.ProgressSink progress) {

        List<GenerateStudyGuideUseCase.ChapterText> chapterTexts = new ArrayList<>();
        for (Chapter chapter : selected) {
            StringBuilder text = new StringBuilder();
            for (PageLayout page : pages) {
                if (!chapter.range().contains(page.pageNo())) continue;
                for (TextBlock block : page.translatableBlocks()) {
                    text.append(translations.getOrDefault(block.id(), block.text())).append('\n');
                }
            }
            chapterTexts.add(new GenerateStudyGuideUseCase.ChapterText(
                    chapter.title(), text.toString()));
        }

        // The guide is written from translated text, so it gets the same terms as a map from
        // source to agreed rendering: it then names concepts the way the chapter did.
        Map<String, String> terms = new TreeMap<>();
        matcher.termsInAll(pages.stream().flatMap(page -> page.translatableBlocks().stream())
                        .map(TextBlock::text).toList())
                .forEach(term -> terms.put(term.sourceTerm(), term.targetTerm()));

        GenerateStudyGuideUseCase.Rendered guide = studyGuides.generate(
                chapterTexts, command.targetLanguage(), terms,
                "%s - study guide (%s)".formatted(textbook.title(), describeSelection(selected)),
                command.dryRun());

        String filename = "%s-%s-%s-study-guide.pdf".formatted(
                slug(textbook.title()), slug(describeSelection(selected)),
                command.targetLanguage().tag());
        store(progress.jobId(), ArtifactKind.STUDY_GUIDE, guide.pdf(), filename, guide.pageCount());
    }

    private void store(
            com.medlearn.shared.JobId jobId, ArtifactKind kind,
            byte[] pdf, String filename, Integer pageCount) {

        BlobStorePort.StoredBlob stored = blobStore.storeBytes(pdf, filename);
        artifacts.insert(new Artifact(
                com.medlearn.shared.ArtifactId.newId(), jobId, kind, stored.ref(), filename,
                stored.byteSize(), Optional.ofNullable(pageCount), java.time.Instant.now()));
    }

    /**
     * Translates everything not already in the memory cache.
     *
     * <p>The first batch is sent on its own and awaited before the rest fan out. A cache
     * entry only becomes readable once the first response has begun, so firing every batch
     * at once would have them all miss the shared prefix and pay full price for it.
     */
    private Map<String, String> translateAll(
            List<TextBlock> blocks, Command command,
            TranslationModelPort model, JobCoordinator.ProgressSink progress,
            Map<String, Integer> pageByBlock, GlossaryMatcher matcher) {

        // A block's memory key covers its text and the glossary terms it contains, so editing
        // a term re-translates exactly the blocks that use it.
        Map<String, TranslationMemoryPort.Key> keyByBlock = new LinkedHashMap<>();
        for (TextBlock block : blocks) {
            keyByBlock.put(block.id(), new TranslationMemoryPort.Key(
                    ContentHash.ofText(block.text()),
                    GlossaryMatcher.fingerprint(matcher.termsIn(block.text()))));
        }

        // Only real translations touch the memory. Dry-run output is fake text, and the
        // memory is keyed by content and language, not model: caching it would serve the fake
        // text to a later live run. Skipping the read also keeps dry runs representative,
        // since every block then goes through expansion and fitting.
        boolean useMemory = model.billable();
        Map<TranslationMemoryPort.Key, String> cached = Map.of();
        if (useMemory) {
            progress.phase("checking translation memory");
            cached = memory.findAll(keyByBlock.values(), command.targetLanguage());
        }

        Map<String, String> translations = new ConcurrentHashMap<>();
        List<TranslationModelPort.SourceBlock> pending = new ArrayList<>();

        for (TextBlock block : blocks) {
            String hit = cached.get(keyByBlock.get(block.id()));
            if (hit != null) {
                translations.put(block.id(), hit);
                progress.advance(1);
            } else {
                pending.add(new TranslationModelPort.SourceBlock(block.id(), block.text()));
            }
        }

        if (pending.isEmpty()) {
            log.info("Every block was already translated; no API calls needed");
            return translations;
        }
        log.info("{} of {} blocks served from translation memory", blocks.size() - pending.size(),
                blocks.size());

        List<List<TranslationModelPort.SourceBlock>> batches =
                BatchPlanner.intoBatches(pending, properties.maxCharsPerBatch());

        progress.phase("translating");
        Map<String, String> sourceById = new HashMap<>();
        pending.forEach(block -> sourceById.put(block.id(), block.text()));

        // Computed once for the whole job and sorted: the glossary sits in the cached prompt
        // prefix, which must be byte-identical across every batch.
        List<GlossaryTerm> promptGlossary = List.copyOf(matcher.termsInAll(sourceById.values()));
        log.info("{} glossary term(s) occur in the text being translated", promptGlossary.size());

        JobRun job = new JobRun(command, model, promptGlossary, translations, sourceById, keyByBlock,
                Collections.synchronizedList(new ArrayList<>()),
                Collections.synchronizedList(new ArrayList<>()), progress);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            // Seed the prompt cache with one request before fanning out. It takes a slot like
            // every other batch: with several jobs running, seeds would otherwise exceed the limit.
            apiSlots.run(() -> runBatch(batches.getFirst(), job));

            List<CompletableFuture<Void>> futures = batches.stream().skip(1)
                    .map(batch -> CompletableFuture.runAsync(
                            () -> apiSlots.run(() -> runBatch(batch, job)), executor))
                    .toList();

            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        }

        // Kept even when cancelled: these blocks passed the quality gate and were paid for,
        // so a re-run should not pay for them again.
        if (useMemory) {
            memory.putAll(List.copyOf(job.toCache()), command.targetLanguage(), model.name());
        }

        // Blocks the model never returned: recorded, and left in the source language rather
        // than silently dropped from the page. Not checked after a cancel, when the skipped
        // batches are missing on purpose.
        if (!progress.cancelled()) {
            for (TranslationModelPort.SourceBlock block : pending) {
                if (!translations.containsKey(block.id())) {
                    job.findings().add(new QualityFinding.MissingBlock(block.id()));
                }
            }
        }
        if (!job.findings().isEmpty()) {
            log.warn("{} quality finding(s) while translating", job.findings().size());
        }
        report(progress, job.findings(), pageByBlock);
        return translations;
    }

    private void runBatch(List<TranslationModelPort.SourceBlock> batch, JobRun job) {
        // Checked after getting an API slot, which may have meant queueing behind other jobs.
        if (job.progress().cancelled()) return;

        TranslationModelPort.Result result = job.model().translate(new TranslationModelPort.Batch(
                batch, job.command().targetLanguage(), job.glossary(), "", ""));

        job.findings().addAll(result.findings());

        for (TranslatedBlock translated : result.blocks()) {
            String source = job.sourceById().get(translated.blockId());
            if (source == null) continue;

            List<QualityFinding> blockFindings = qualityGate.inspect(
                    translated.blockId(), source, translated.targetText());
            job.findings().addAll(blockFindings);

            job.translations().put(translated.blockId(), translated.targetText());
            // Only cache what passed: storing a suspect translation would make the error
            // permanent and invisible on every later run.
            if (blockFindings.isEmpty()) {
                job.toCache().add(new TranslationMemoryPort.Entry(
                        job.keyByBlock().get(translated.blockId()), translated.targetText()));
            }
        }
        job.progress().advance(batch.size());
    }

    /** What every batch of one translation job shares. */
    private record JobRun(
            Command command,
            TranslationModelPort model,
            List<GlossaryTerm> glossary,
            Map<String, String> translations,
            Map<String, String> sourceById,
            Map<String, TranslationMemoryPort.Key> keyByBlock,
            List<QualityFinding> findings,
            List<TranslationMemoryPort.Entry> toCache,
            JobCoordinator.ProgressSink progress) {}

    /** Persists findings with the job, so they reach the API and the UI, not only the log. */
    private void report(JobCoordinator.ProgressSink progress, List<QualityFinding> findings,
                        Map<String, Integer> pageByBlock) {
        jobs.recordFindings(progress.jobId(), FindingReport.toJobFindings(findings, pageByBlock));
    }

    private List<Chapter> resolveChapters(Command command) {
        if (!command.chapterIds().isEmpty()) {
            return chapters.findAllById(command.chapterIds());
        }
        return chapters.findByTextbook(command.textbookId()).stream()
                .filter(chapter -> chapter.ordinal() >= command.fromOrdinal()
                        && chapter.ordinal() <= command.toOrdinal())
                .toList();
    }

    private TranslationModelPort selectModel(boolean dryRun) {
        return models.stream()
                .filter(model -> model.billable() != dryRun)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(dryRun
                        ? "no dry-run translator is configured"
                        : "no live translator is configured - set ANTHROPIC_API_KEY, or use dryRun"));
    }

    private PdfRendererPort selectRenderer(RenderMode mode) {
        return renderers.stream()
                .filter(renderer -> renderer.mode() == mode)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no renderer for mode " + mode));
    }

    private static String describeSelection(List<Chapter> selected) {
        if (selected.size() == 1) return selected.getFirst().title();
        return "%s to %s".formatted(
                selected.getFirst().title(), selected.getLast().title());
    }

    private static String slug(String text) {
        String slug = text.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        return slug.length() > 48 ? slug.substring(0, 48) : slug;
    }

    /** The coordinator owns the job id; the sink is the only handle the work has to it. */
    private static JobId currentJobId(JobCoordinator.ProgressSink progress) {
        return progress.jobId();
    }

    /**
     * @param fromOrdinal inclusive chapter ordinal, used when no explicit ids are given
     * @param dryRun      runs the whole pipeline with a free, non-calling translator
     */
    public record Command(
            TextbookId textbookId,
            List<ChapterId> chapterIds,
            int fromOrdinal,
            int toOrdinal,
            LanguageTag targetLanguage,
            RenderMode renderMode,
            boolean includeStudyGuide,
            boolean dryRun) {

        public Command {
            chapterIds = chapterIds == null ? List.of() : List.copyOf(chapterIds);
            if (renderMode == null) renderMode = RenderMode.OVERLAY;
            if (targetLanguage == null) throw new IllegalArgumentException("targetLanguage required");
        }
    }

    public sealed interface Outcome {
        record Accepted(JobId jobId) implements Outcome {}

        record Rejected(DomainError error) implements Outcome {}
    }
}
