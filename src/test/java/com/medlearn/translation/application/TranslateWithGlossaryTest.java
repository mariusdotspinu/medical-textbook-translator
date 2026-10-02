package com.medlearn.translation.application;

import com.medlearn.ingestion.domain.BlockBox;
import com.medlearn.ingestion.domain.FontInfo;
import com.medlearn.ingestion.domain.PageKind;
import com.medlearn.ingestion.domain.PageLayout;
import com.medlearn.ingestion.domain.TextBlock;
import com.medlearn.ingestion.domain.TextRole;
import com.medlearn.ingestion.domain.port.PageLayoutRepository;
import com.medlearn.jobs.application.JobCoordinator;
import com.medlearn.jobs.domain.port.ArtifactRepository;
import com.medlearn.learning.application.GenerateStudyGuideUseCase;
import com.medlearn.library.domain.Chapter;
import com.medlearn.library.domain.Textbook;
import com.medlearn.library.domain.TextbookStatus;
import com.medlearn.library.domain.port.ChapterRepository;
import com.medlearn.library.domain.port.TextbookRepository;
import com.medlearn.platform.config.TranslationProperties;
import com.medlearn.shared.BlobRef;
import com.medlearn.shared.ChapterId;
import com.medlearn.shared.ContentHash;
import com.medlearn.shared.JobId;
import com.medlearn.shared.LanguageTag;
import com.medlearn.shared.PageRange;
import com.medlearn.shared.TextbookId;
import com.medlearn.shared.port.BlobStorePort;
import com.medlearn.translation.domain.GlossaryMatcher;
import com.medlearn.translation.domain.GlossaryTerm;
import com.medlearn.translation.domain.GlossaryTerm.TermKind;
import com.medlearn.translation.domain.RenderMode;
import com.medlearn.translation.domain.TranslatedBlock;
import com.medlearn.translation.domain.port.PdfRendererPort;
import com.medlearn.translation.domain.port.TranslationMemoryPort;
import com.medlearn.translation.domain.port.TranslationModelPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The glossary reaches the prompt, the memory key and the study guide. */
class TranslateWithGlossaryTest {

    private static final LanguageTag RO = LanguageTag.of("ro");

    private final PageLayoutRepository pageLayouts = mock(PageLayoutRepository.class);
    private final BlobStorePort blobStore = mock(BlobStorePort.class);
    private final TranslationMemoryPort memory = mock(TranslationMemoryPort.class);
    private final PdfRendererPort renderer = mock(PdfRendererPort.class);
    private final TranslationModelPort model = mock(TranslationModelPort.class);
    private final GlossaryService glossary = mock(GlossaryService.class);
    private final GenerateStudyGuideUseCase studyGuides = mock(GenerateStudyGuideUseCase.class);
    private final TranslationProperties properties = new TranslationProperties(24_000, 2, 0.4, 2.5);

    private final TranslateChaptersUseCase useCase = new TranslateChaptersUseCase(
            mock(TextbookRepository.class), mock(ChapterRepository.class), pageLayouts, blobStore,
            memory, mock(ArtifactRepository.class), mock(JobCoordinator.class),
            new TranslationQualityGate(properties), List.of(renderer), List.of(model),
            studyGuides, properties, glossary, new ApiSlots(properties));

    private final TextbookId bookId = TextbookId.newId();
    private final Textbook book = Textbook.justUploaded(bookId, "Cardiology", Optional.empty(), 1, 1,
            ContentHash.ofText("b"), new BlobRef("ab/b.pdf")).withStatus(TextbookStatus.READY);
    private final Chapter chapter = new Chapter(ChapterId.newId(), bookId, Optional.empty(), 0, 0,
            "Heart failure", PageRange.of(1, 1));

    private final GlossaryTerm heartFailure = term("heart failure", "insuficiență cardiacă");
    private final GlossaryTerm stroke = term("stroke", "accident vascular cerebral");   // not in text
    private final TextBlock withTerm = block("p1-b0", "Heart failure reduces cardiac output.");
    private final TextBlock withoutTerm = block("p1-b1", "Output falls at rest and on exertion.");

    @BeforeEach
    void setUp() {
        when(pageLayouts.findRange(bookId, PageRange.of(1, 1))).thenReturn(List.of(
                new PageLayout(1, 595, 842, PageKind.TEXT, List.of(withTerm, withoutTerm))));
        when(glossary.termsFor(bookId, RO)).thenReturn(List.of(stroke, heartFailure));
        when(memory.findAll(any(), any())).thenReturn(Map.of());
        when(model.name()).thenReturn("model");
        when(model.translate(any())).thenAnswer(invocation -> {
            TranslationModelPort.Batch batch = invocation.getArgument(0);
            return new TranslationModelPort.Result(batch.blocks().stream()
                    .map(b -> new TranslatedBlock(b.id(), b.text() + " (ro)")).toList(),
                    List.of(), TranslationModelPort.Usage.none());
        });
        when(renderer.mode()).thenReturn(RenderMode.OVERLAY);
        when(renderer.render(any())).thenReturn(new PdfRendererPort.RenderResult(new byte[]{1}, 1, List.of()));
        when(blobStore.storeBytes(any(), anyString())).thenReturn(
                new BlobStorePort.StoredBlob(new BlobRef("cd/out.pdf"), ContentHash.ofText("o"), 1));
        when(studyGuides.generate(any(), any(), any(), any(), eq(false)))
                .thenReturn(new GenerateStudyGuideUseCase.Rendered(new byte[]{1}, 1, 1));
    }

    @Test
    @DisplayName("a live run builds the glossary first and sends only the terms the text contains")
    void liveRunUsesGlossary() {
        when(model.billable()).thenReturn(true);

        useCase.run(command(false), book, List.of(chapter), progress());

        verify(glossary).extend(eq(bookId), eq(RO), any(), any());
        ArgumentCaptor<TranslationModelPort.Batch> batch = ArgumentCaptor.forClass(TranslationModelPort.Batch.class);
        verify(model).translate(batch.capture());
        assertThat(batch.getValue().glossary()).containsExactly(heartFailure);
    }

    @Test
    @DisplayName("only the block containing a term has a glossary-specific memory key")
    void memoryKeyFollowsTermsInBlock() {
        when(model.billable()).thenReturn(true);

        useCase.run(command(false), book, List.of(chapter), progress());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<TranslationMemoryPort.Key>> keys = ArgumentCaptor.forClass(Collection.class);
        verify(memory).findAll(keys.capture(), eq(RO));
        assertThat(keys.getValue()).containsExactlyInAnyOrder(
                new TranslationMemoryPort.Key(ContentHash.ofText(withTerm.text()),
                        GlossaryMatcher.fingerprint(List.of(heartFailure))),
                new TranslationMemoryPort.Key(ContentHash.ofText(withoutTerm.text()),
                        GlossaryMatcher.fingerprint(List.of())));
    }

    @Test
    @DisplayName("a dry run reads the glossary but never extends it")
    void dryRunDoesNotExtract() {
        when(model.billable()).thenReturn(false);

        useCase.run(command(true), book, List.of(chapter), progress());

        verify(glossary, never()).extend(any(), any(), any(), any());
        ArgumentCaptor<TranslationModelPort.Batch> batch = ArgumentCaptor.forClass(TranslationModelPort.Batch.class);
        verify(model).translate(batch.capture());
        assertThat(batch.getValue().glossary()).containsExactly(heartFailure);
    }

    @Test
    @DisplayName("the study guide gets the terms that occur in the chapter")
    void studyGuideGetsTerms() {
        when(model.billable()).thenReturn(true);

        useCase.run(new TranslateChaptersUseCase.Command(bookId, List.of(), 0, Integer.MAX_VALUE, RO,
                RenderMode.OVERLAY, true, false), book, List.of(chapter), progress());

        verify(studyGuides).generate(any(), eq(RO),
                eq(Map.of("heart failure", "insuficiență cardiacă")), any(), eq(false));
    }

    private TranslateChaptersUseCase.Command command(boolean dryRun) {
        return new TranslateChaptersUseCase.Command(bookId, List.of(), 0, Integer.MAX_VALUE, RO,
                RenderMode.OVERLAY, false, dryRun);
    }

    private static GlossaryTerm term(String source, String target) {
        return new GlossaryTerm(null, source, target, RO, TermKind.CONDITION, 0, false);
    }

    private static TextBlock block(String id, String text) {
        return new TextBlock(id, text, new BlockBox(50, 100, 400, 30),
                new FontInfo("Minion-Regular", 9.5f, false, false), 0, 0, TextRole.BODY);
    }

    private static JobCoordinator.ProgressSink progress() {
        JobId jobId = JobId.newId();
        return new JobCoordinator.ProgressSink() {
            @Override public JobId jobId() { return jobId; }
            @Override public void total(int totalUnits) {}
            @Override public void advance(int completedUnits) {}
            @Override public void phase(String phase) {}
            @Override public boolean cancelled() { return false; }
        };
    }
}
