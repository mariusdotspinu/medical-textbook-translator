package com.medlearn.translation.application;

import com.medlearn.ingestion.domain.PageLayout;
import com.medlearn.ingestion.domain.TextBlock;
import com.medlearn.jobs.application.JobCoordinator;
import com.medlearn.library.domain.port.TextbookRepository;
import com.medlearn.shared.LanguageTag;
import com.medlearn.shared.PageRange;
import com.medlearn.shared.TextbookId;
import com.medlearn.translation.domain.GlossaryMatcher;
import com.medlearn.translation.domain.GlossaryTerm;
import com.medlearn.translation.domain.GlossaryTerm.TermKind;
import com.medlearn.translation.domain.GlossaryTermId;
import com.medlearn.translation.domain.port.GlossaryRepository;
import com.medlearn.translation.domain.port.TermExtractionPort;
import com.medlearn.translation.domain.port.TermExtractionPort.ProposedTerm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A book's glossary: built automatically before translation, corrected by readers.
 *
 * <p><b>Building.</b> Before a live translation, pages not yet scanned for this language are
 * sent to the model in chunks; it proposes the terms whose rendering must stay identical
 * throughout the book. Each page is scanned once per language, so re-translating costs no
 * extraction at all. Dry runs never extract: a fake rendering in the glossary would reach
 * every later live run.
 *
 * <p><b>Stability.</b> Extraction only ever adds terms. A term already in the glossary keeps
 * its rendering, because pages may already use it; only a reader's edit changes it, and an
 * edit pins the term. Each edit re-translates exactly the blocks containing the term, since
 * the glossary is part of their translation-memory key.
 *
 * <p><b>Trust.</b> Proposals are checked before they are stored: the source term must really
 * occur in the scanned text (whole words), must not already be in the glossary, and must
 * translate to something other than itself.
 */
@Service
public class GlossaryService {

    private static final Logger log = LoggerFactory.getLogger(GlossaryService.class);

    /** Source characters per extraction request: roughly a 20-page stretch of a chapter. */
    static final int CHUNK_CHARS = 60_000;
    static final int MAX_TERMS_PER_CHUNK = 40;
    private static final int MAX_TERM_LENGTH = 80;

    private final GlossaryRepository glossary;
    private final TextbookRepository textbooks;
    private final List<TermExtractionPort> extractors;
    private final ApiSlots apiSlots;

    public GlossaryService(
            GlossaryRepository glossary,
            TextbookRepository textbooks,
            List<TermExtractionPort> extractors,
            ApiSlots apiSlots) {
        this.glossary = glossary;
        this.textbooks = textbooks;
        this.extractors = List.copyOf(extractors);
        this.apiSlots = apiSlots;
    }

    // --- building, inside a translation job ---------------------------------

    /**
     * Scans the pages not yet scanned for this language and adds the terms found.
     *
     * <p>Never fails the translation: if extraction is unavailable or a request fails, the
     * chapter is translated with the glossary it already has, and the unscanned pages are
     * tried again next time.
     *
     * @return how many terms were added
     */
    public int extend(TextbookId textbookId, LanguageTag language, List<PageLayout> pages,
                      JobCoordinator.ProgressSink progress) {
        if (extractors.isEmpty() || pages.isEmpty()) return 0;
        TermExtractionPort extractor = extractors.getFirst();

        PageRange span = PageRange.of(
                pages.stream().mapToInt(PageLayout::pageNo).min().orElseThrow(),
                pages.stream().mapToInt(PageLayout::pageNo).max().orElseThrow());
        Set<Integer> covered = glossary.coveredPages(textbookId, language, span);
        List<PageLayout> pending = pages.stream().filter(page -> !covered.contains(page.pageNo())).toList();
        if (pending.isEmpty()) return 0;

        int added = 0;
        for (Chunk chunk : chunk(pending)) {
            if (progress.cancelled()) break;

            List<GlossaryTerm> known = glossary.findAll(textbookId, language);
            if (!chunk.text().isBlank()) {
                List<ProposedTerm> proposals;
                try {
                    proposals = apiSlots.call(() -> progress.cancelled() ? List.<ProposedTerm>of()
                            : extractor.propose(new TermExtractionPort.Request(
                                    chunk.text(), language,
                                    known.stream().map(GlossaryTerm::sourceTerm).sorted().toList(),
                                    MAX_TERMS_PER_CHUNK)));
                } catch (RuntimeException e) {
                    log.warn("Glossary extraction failed for {} ({}), pages {}-{}; continuing with "
                                    + "the existing glossary: {}", textbookId, language,
                            chunk.firstPage(), chunk.lastPage(), e.getMessage());
                    break;
                }
                if (progress.cancelled()) break;

                List<GlossaryTerm> accepted = accept(proposals, known, chunk.text(), language);
                glossary.addNew(textbookId, accepted);
                added += accepted.size();

                List<GlossaryTerm> now = glossary.findAll(textbookId, language);
                glossary.addOccurrences(textbookId, language, GlossaryMatcher.of(now)
                        .countsIn(chunk.text()).entrySet().stream()
                        .collect(Collectors.toMap(entry -> entry.getKey().sourceTerm(), Map.Entry::getValue)));
            }
            glossary.markCovered(textbookId, language, chunk.pages());
        }

        log.info("Glossary for {} ({}): {} term(s) added from {} unscanned page(s)",
                textbookId, language, added, pending.size());
        return added;
    }

    /** The checks a proposal must pass before it can affect every later page. */
    static List<GlossaryTerm> accept(
            List<ProposedTerm> proposals, List<GlossaryTerm> known, String chunkText,
            LanguageTag language) {
        Set<String> taken = new HashSet<>();
        known.forEach(term -> taken.add(lower(term.sourceTerm())));

        List<GlossaryTerm> accepted = new ArrayList<>();
        for (ProposedTerm proposal : proposals) {
            if (accepted.size() >= MAX_TERMS_PER_CHUNK) break;
            String source = proposal.sourceTerm() == null ? "" : proposal.sourceTerm().strip();
            String target = proposal.targetTerm() == null ? "" : proposal.targetTerm().strip();
            if (source.isEmpty() || target.isEmpty()) continue;
            if (source.length() > MAX_TERM_LENGTH || target.length() > MAX_TERM_LENGTH) continue;
            if (source.equalsIgnoreCase(target)) continue;
            if (!taken.add(lower(source))) continue;

            GlossaryTerm candidate = new GlossaryTerm(
                    GlossaryTermId.newId(), source, target, language, proposal.kind(), 0, false);
            // A term the text does not contain is a hallucination, or a paraphrase that
            // would never match a block; either way it must not enter the glossary.
            if (GlossaryMatcher.of(List.of(candidate)).termsIn(chunkText).isEmpty()) {
                taken.remove(lower(source));
                continue;
            }
            accepted.add(candidate);
        }
        return accepted;
    }

    private static List<Chunk> chunk(List<PageLayout> pages) {
        List<Chunk> chunks = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        List<Integer> chunkPages = new ArrayList<>();

        for (PageLayout page : pages) {
            String pageText = page.translatableBlocks().stream()
                    .map(TextBlock::text).collect(Collectors.joining("\n"));
            if (!chunkPages.isEmpty() && text.length() + pageText.length() > CHUNK_CHARS) {
                chunks.add(new Chunk(List.copyOf(chunkPages), text.toString()));
                text.setLength(0);
                chunkPages.clear();
            }
            chunkPages.add(page.pageNo());
            if (!pageText.isBlank()) text.append(pageText).append("\n\n");
        }
        if (!chunkPages.isEmpty()) chunks.add(new Chunk(List.copyOf(chunkPages), text.toString()));
        return chunks;
    }

    private record Chunk(List<Integer> pages, String text) {
        int firstPage() { return pages.getFirst(); }
        int lastPage() { return pages.getLast(); }
    }

    // --- reading, for translation -------------------------------------------

    public List<GlossaryTerm> termsFor(TextbookId textbookId, LanguageTag language) {
        return glossary.findAll(textbookId, language);
    }

    // --- reader edits ---------------------------------------------------------

    /** @return empty if the textbook does not exist */
    public Optional<List<GlossaryTerm>> list(TextbookId textbookId, LanguageTag language) {
        if (textbooks.findById(textbookId).isEmpty()) return Optional.empty();
        return Optional.of(glossary.findAll(textbookId, language));
    }

    /**
     * Adds or corrects a term and pins it, so extraction never changes it.
     *
     * @return empty if the textbook does not exist
     */
    public Optional<GlossaryTerm> save(TextbookId textbookId, LanguageTag language,
                                       String sourceTerm, String targetTerm, TermKind kind) {
        if (textbooks.findById(textbookId).isEmpty()) return Optional.empty();
        GlossaryTerm term = glossary.findBySource(textbookId, language, sourceTerm)
                .map(existing -> existing.pinnedAs(targetTerm, kind))
                .orElseGet(() -> new GlossaryTerm(
                        GlossaryTermId.newId(), sourceTerm, targetTerm, language, kind, 0, true));
        GlossaryTerm saved = glossary.save(textbookId, term);
        log.info("Glossary term pinned for {} ({}): {} -> {}",
                textbookId, language, saved.sourceTerm(), saved.targetTerm());
        return Optional.of(saved);
    }

    public boolean delete(TextbookId textbookId, LanguageTag language, GlossaryTermId id) {
        return glossary.delete(textbookId, language, id);
    }

    private static String lower(String text) {
        return text.toLowerCase(Locale.ROOT);
    }
}
