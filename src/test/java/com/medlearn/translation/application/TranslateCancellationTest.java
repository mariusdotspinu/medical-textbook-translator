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
import com.medlearn.translation.domain.RenderMode;
import com.medlearn.translation.domain.TranslatedBlock;
import com.medlearn.translation.domain.port.PdfRendererPort;
import com.medlearn.translation.domain.port.TranslationMemoryPort;
import com.medlearn.translation.domain.port.TranslationModelPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A translation cancelled mid-way stops sending batches and produces no artifacts. */
class TranslateCancellationTest {

    private static final int BATCHES = 6;

    private final TextbookRepository textbooks = mock(TextbookRepository.class);
    private final ChapterRepository chapters = mock(ChapterRepository.class);
    private final PageLayoutRepository pageLayouts = mock(PageLayoutRepository.class);
    private final BlobStorePort blobStore = mock(BlobStorePort.class);
    private final TranslationMemoryPort memory = mock(TranslationMemoryPort.class);
    private final ArtifactRepository artifacts = mock(ArtifactRepository.class);
    private final PdfRendererPort renderer = mock(PdfRendererPort.class);
    private final TranslationModelPort model = mock(TranslationModelPort.class);
    private final GenerateStudyGuideUseCase studyGuides = mock(GenerateStudyGuideUseCase.class);

    // One block per batch: each block is longer than half the character budget.
    private final TranslationProperties properties = new TranslationProperties(150, 2, 0.4, 2.5);

    private final TranslateChaptersUseCase useCase = new TranslateChaptersUseCase(
            textbooks, chapters, pageLayouts, blobStore, memory, artifacts,
            mock(JobCoordinator.class), new TranslationQualityGate(properties),
            List.of(renderer), List.of(model), studyGuides, properties,
            mock(GlossaryService.class), new ApiSlots(properties));

    @Test
    @DisplayName("cancelling during the first batch stops the fan-out, rendering and study guide")
    void cancelDuringTranslationProducesNoArtifacts() {
        TextbookId textbookId = TextbookId.newId();
        Textbook textbook = Textbook.justUploaded(
                        textbookId, "Cardiology", Optional.empty(), 1, 10,
                        ContentHash.ofText("book"), new BlobRef("ab/abc.pdf"))
                .withStatus(TextbookStatus.READY);
        Chapter chapter = new Chapter(ChapterId.newId(), textbookId, Optional.empty(), 0, 0,
                "Heart failure", PageRange.of(1, 1));

        when(pageLayouts.findRange(textbookId, PageRange.of(1, 1))).thenReturn(List.of(pageWithBlocks()));
        when(memory.findAll(any(), any())).thenReturn(Map.of());
        when(renderer.mode()).thenReturn(RenderMode.OVERLAY);
        when(model.billable()).thenReturn(true);     // live: only live runs use memory
        when(model.name()).thenReturn("fake");

        Progress progress = new Progress();
        AtomicInteger calls = new AtomicInteger();
        when(model.translate(any())).thenAnswer(invocation -> {
            calls.incrementAndGet();
            progress.cancelled.set(true);       // the reader cancels while batch 1 is in flight
            TranslationModelPort.Batch batch = invocation.getArgument(0);
            return new TranslationModelPort.Result(
                    batch.blocks().stream()
                            .map(block -> new TranslatedBlock(block.id(), block.text()))
                            .toList(),
                    List.of(), TranslationModelPort.Usage.none());
        });

        useCase.run(command(textbookId), textbook, List.of(chapter), progress);

        assertThat(calls.get()).isEqualTo(1);
        verify(memory).putAll(any(), any(), any());   // the paid-for batch is kept
        verify(renderer, never()).render(any());
        verify(studyGuides, never()).generate(any(), any(), any(), any(), anyBoolean());
        verify(artifacts, never()).insert(any());
    }

    private static PageLayout pageWithBlocks() {
        List<TextBlock> blocks = IntStream.range(0, BATCHES)
                .mapToObj(i -> new TextBlock(
                        "p1-b" + i,
                        "Paragraph %d about ventricular remodelling after infarction, ".formatted(i)
                                + "repeated to exceed half of the batch budget.",
                        new BlockBox(50, 100 + i * 40, 400, 30),
                        new FontInfo("Minion-Regular", 9.5f, false, false),
                        0, i, TextRole.BODY))
                .toList();
        return new PageLayout(1, 595, 842, PageKind.TEXT, blocks);
    }

    private static TranslateChaptersUseCase.Command command(TextbookId textbookId) {
        return new TranslateChaptersUseCase.Command(
                textbookId, List.of(), 0, Integer.MAX_VALUE, LanguageTag.of("ro"),
                RenderMode.OVERLAY, true, false);
    }

    private static final class Progress implements JobCoordinator.ProgressSink {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final JobId jobId = JobId.newId();

        @Override public JobId jobId() { return jobId; }
        @Override public void total(int totalUnits) {}
        @Override public void advance(int completedUnits) {}
        @Override public void phase(String phase) {}
        @Override public boolean cancelled() { return cancelled.get(); }
    }
}
