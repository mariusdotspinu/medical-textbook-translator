package com.medlearn.translation.application;

import com.medlearn.ingestion.domain.BlockBox;
import com.medlearn.ingestion.domain.FontInfo;
import com.medlearn.ingestion.domain.PageKind;
import com.medlearn.ingestion.domain.PageLayout;
import com.medlearn.ingestion.domain.TextBlock;
import com.medlearn.ingestion.domain.TextRole;
import com.medlearn.ingestion.domain.port.PageLayoutRepository;
import com.medlearn.jobs.application.JobCoordinator;
import com.medlearn.jobs.domain.JobSpec;
import com.medlearn.jobs.domain.JobType;
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
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TranslationConcurrencyAndSpecTest {

    private static final int LIMIT = 2;

    private final TextbookRepository textbooks = mock(TextbookRepository.class);
    private final ChapterRepository chapters = mock(ChapterRepository.class);
    private final PageLayoutRepository pageLayouts = mock(PageLayoutRepository.class);
    private final BlobStorePort blobStore = mock(BlobStorePort.class);
    private final TranslationMemoryPort memory = mock(TranslationMemoryPort.class);
    private final PdfRendererPort renderer = mock(PdfRendererPort.class);
    private final TranslationModelPort model = mock(TranslationModelPort.class);
    private final JobCoordinator jobs = mock(JobCoordinator.class);

    // One block per batch, so every block is a separate request.
    private final TranslationProperties properties = new TranslationProperties(150, LIMIT, 0.4, 2.5);

    private final TranslateChaptersUseCase useCase = new TranslateChaptersUseCase(
            textbooks, chapters, pageLayouts, blobStore, memory, mock(ArtifactRepository.class), jobs,
            new TranslationQualityGate(properties), List.of(renderer), List.of(model),
            mock(GenerateStudyGuideUseCase.class), properties,
            mock(GlossaryService.class), new ApiSlots(properties));

    private final TextbookId textbookId = TextbookId.newId();
    private final Textbook textbook = Textbook.justUploaded(
                    textbookId, "Endocrinology", Optional.empty(), 1, 10,
                    ContentHash.ofText("book"), new BlobRef("ab/abc.pdf"))
            .withStatus(TextbookStatus.READY);
    private final Chapter chapter = new Chapter(ChapterId.newId(), textbookId, Optional.empty(),
            0, 0, "Thyroid", PageRange.of(1, 1));

    @Test
    @DisplayName("max-in-flight caps requests across concurrent jobs, not per job")
    void limitIsSharedAcrossJobs() {
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        AtomicInteger calls = new AtomicInteger();

        when(pageLayouts.findRange(textbookId, PageRange.of(1, 1))).thenReturn(List.of(pageOf(6)));
        when(memory.findAll(any(), any())).thenReturn(Map.of());
        when(model.billable()).thenReturn(false);
        when(model.name()).thenReturn("fake");
        when(model.translate(any())).thenAnswer(invocation -> {
            peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            calls.incrementAndGet();
            Thread.sleep(40);
            inFlight.decrementAndGet();
            TranslationModelPort.Batch batch = invocation.getArgument(0);
            return new TranslationModelPort.Result(
                    batch.blocks().stream().map(b -> new TranslatedBlock(b.id(), b.text())).toList(),
                    List.of(), TranslationModelPort.Usage.none());
        });
        when(renderer.mode()).thenReturn(RenderMode.OVERLAY);
        when(renderer.render(any())).thenReturn(new PdfRendererPort.RenderResult(new byte[]{1}, 1, List.of()));
        when(blobStore.storeBytes(any(), anyString())).thenReturn(
                new BlobStorePort.StoredBlob(new BlobRef("cd/out.pdf"), ContentHash.ofText("out"), 1));

        // Three jobs at once, each wanting up to LIMIT requests of its own.
        List<CompletableFuture<Void>> runs = IntStream.range(0, 3)
                .mapToObj(i -> CompletableFuture.runAsync(() ->
                        useCase.run(command(List.of()), textbook, List.of(chapter), new Progress())))
                .toList();
        runs.forEach(CompletableFuture::join);

        assertThat(calls.get()).isEqualTo(3 * 6);
        assertThat(peak.get()).isLessThanOrEqualTo(LIMIT);
    }

    @Test
    @DisplayName("a translation job records its language, chapters and options")
    void submitRecordsWhatTheJobIsAbout() {
        when(textbooks.findById(textbookId)).thenReturn(Optional.of(textbook));
        when(chapters.findAllById(List.of(chapter.id()))).thenReturn(List.of(chapter));
        when(jobs.submit(any(JobSpec.class), anyString(), any())).thenReturn(JobId.newId());

        useCase.submit(command(List.of(chapter.id())));

        ArgumentCaptor<JobSpec> spec = ArgumentCaptor.forClass(JobSpec.class);
        verify(jobs).submit(spec.capture(), eq("preparing"), any());
        assertThat(spec.getValue().type()).isEqualTo(JobType.TRANSLATE);
        assertThat(spec.getValue().targetLanguage()).contains(LanguageTag.of("ro"));
        assertThat(spec.getValue().chapterIds()).containsExactly(chapter.id());
        assertThat(spec.getValue().options()).containsEntry("renderMode", "OVERLAY")
                .containsEntry("includeStudyGuide", "false")
                .containsEntry("dryRun", "true");
    }

    private static PageLayout pageOf(int blocks) {
        return new PageLayout(1, 595, 842, PageKind.TEXT, IntStream.range(0, blocks)
                .mapToObj(i -> new TextBlock("p1-b" + i,
                        "Paragraph %d on thyroid hormone feedback through the pituitary axis, ".formatted(i)
                                + "long enough to fill a batch by itself.",
                        new BlockBox(50, 100 + i * 40, 400, 30),
                        new FontInfo("Minion-Regular", 9.5f, false, false), 0, i, TextRole.BODY))
                .toList());
    }

    private TranslateChaptersUseCase.Command command(List<ChapterId> chapterIds) {
        return new TranslateChaptersUseCase.Command(
                textbookId, chapterIds, 0, Integer.MAX_VALUE, LanguageTag.of("ro"),
                RenderMode.OVERLAY, false, true);
    }

    private static final class Progress implements JobCoordinator.ProgressSink {
        private final JobId jobId = JobId.newId();

        @Override public JobId jobId() { return jobId; }
        @Override public void total(int totalUnits) {}
        @Override public void advance(int completedUnits) {}
        @Override public void phase(String phase) {}
        @Override public boolean cancelled() { return false; }
    }
}
