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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Translation memory holds real translations only: dry runs neither read nor write it. */
class TranslationMemoryUsageTest {

    private final PageLayoutRepository pageLayouts = mock(PageLayoutRepository.class);
    private final BlobStorePort blobStore = mock(BlobStorePort.class);
    private final TranslationMemoryPort memory = mock(TranslationMemoryPort.class);
    private final PdfRendererPort renderer = mock(PdfRendererPort.class);
    private final TranslationModelPort dryRun = model("dry-run", false);
    private final TranslationModelPort live = model("claude", true);

    private final TranslationProperties properties = new TranslationProperties(24_000, 2, 0.4, 2.5);

    private final TranslateChaptersUseCase useCase = new TranslateChaptersUseCase(
            mock(TextbookRepository.class), mock(ChapterRepository.class), pageLayouts, blobStore,
            memory, mock(ArtifactRepository.class), mock(JobCoordinator.class),
            new TranslationQualityGate(properties), List.of(renderer), List.of(dryRun, live),
            mock(GenerateStudyGuideUseCase.class), properties,
            mock(GlossaryService.class), new ApiSlots(properties));

    private final TextbookId textbookId = TextbookId.newId();
    private final Textbook textbook = Textbook.justUploaded(
                    textbookId, "Nephrology", Optional.empty(), 1, 10,
                    ContentHash.ofText("book"), new BlobRef("ab/abc.pdf"))
            .withStatus(TextbookStatus.READY);
    private final Chapter chapter = new Chapter(ChapterId.newId(), textbookId, Optional.empty(),
            0, 0, "Acute kidney injury", PageRange.of(1, 1));

    @BeforeEach
    void setUp() {
        TextBlock block = new TextBlock("p1-b0", "Creatinine rises within 48 hours.",
                new BlockBox(50, 100, 400, 30),
                new FontInfo("Minion-Regular", 9.5f, false, false), 0, 0, TextRole.BODY);
        when(pageLayouts.findRange(textbookId, PageRange.of(1, 1)))
                .thenReturn(List.of(new PageLayout(1, 595, 842, PageKind.TEXT, List.of(block))));
        when(memory.findAll(any(), any())).thenReturn(Map.of());
        when(renderer.mode()).thenReturn(RenderMode.OVERLAY);
        when(renderer.render(any())).thenReturn(new PdfRendererPort.RenderResult(new byte[]{1}, 1, List.of()));
        when(blobStore.storeBytes(any(), anyString())).thenReturn(
                new BlobStorePort.StoredBlob(new BlobRef("cd/out.pdf"), ContentHash.ofText("out"), 1));
    }

    @Test
    @DisplayName("a dry run neither reads nor writes translation memory")
    void dryRunBypassesMemory() {
        useCase.run(command(true), textbook, List.of(chapter), new Progress());

        verify(dryRun).translate(any());
        verify(memory, never()).findAll(any(), any());
        verify(memory, never()).putAll(any(), any(), any());
    }

    @Test
    @DisplayName("a live run reads memory first and caches what it translated")
    void liveRunUsesMemory() {
        useCase.run(command(false), textbook, List.of(chapter), new Progress());

        verify(live).translate(any());
        verify(memory).findAll(any(), any());
        verify(memory).putAll(any(), any(), eq("claude"));
    }

    private static TranslationModelPort model(String name, boolean billable) {
        TranslationModelPort model = mock(TranslationModelPort.class);
        when(model.name()).thenReturn(name);
        when(model.billable()).thenReturn(billable);
        when(model.translate(any())).thenAnswer(invocation -> {
            TranslationModelPort.Batch batch = invocation.getArgument(0);
            return new TranslationModelPort.Result(
                    batch.blocks().stream()
                            .map(block -> new TranslatedBlock(block.id(), "Creatinina crește în 48 de ore."))
                            .toList(),
                    List.of(), TranslationModelPort.Usage.none());
        });
        return model;
    }

    private TranslateChaptersUseCase.Command command(boolean dryRunRequested) {
        return new TranslateChaptersUseCase.Command(
                textbookId, List.of(), 0, Integer.MAX_VALUE, LanguageTag.of("ro"),
                RenderMode.OVERLAY, false, dryRunRequested);
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
