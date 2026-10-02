package com.medlearn.translation.application;

import com.medlearn.ingestion.domain.BlockBox;
import com.medlearn.ingestion.domain.FontInfo;
import com.medlearn.ingestion.domain.PageKind;
import com.medlearn.ingestion.domain.PageLayout;
import com.medlearn.ingestion.domain.TextBlock;
import com.medlearn.ingestion.domain.TextRole;
import com.medlearn.ingestion.domain.port.PageLayoutRepository;
import com.medlearn.jobs.application.JobCoordinator;
import com.medlearn.jobs.domain.JobFinding;
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
import com.medlearn.translation.domain.QualityFinding;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Quality findings are recorded with the job, not just logged. */
class TranslateFindingsTest {

    private final TextbookRepository textbooks = mock(TextbookRepository.class);
    private final ChapterRepository chapters = mock(ChapterRepository.class);
    private final PageLayoutRepository pageLayouts = mock(PageLayoutRepository.class);
    private final BlobStorePort blobStore = mock(BlobStorePort.class);
    private final TranslationMemoryPort memory = mock(TranslationMemoryPort.class);
    private final ArtifactRepository artifacts = mock(ArtifactRepository.class);
    private final PdfRendererPort renderer = mock(PdfRendererPort.class);
    private final TranslationModelPort model = mock(TranslationModelPort.class);
    private final JobCoordinator jobs = mock(JobCoordinator.class);

    private final TranslationProperties properties = new TranslationProperties(24_000, 2, 0.4, 2.5);

    private final TranslateChaptersUseCase useCase = new TranslateChaptersUseCase(
            textbooks, chapters, pageLayouts, blobStore, memory, artifacts, jobs,
            new TranslationQualityGate(properties), List.of(renderer), List.of(model),
            mock(GenerateStudyGuideUseCase.class), properties,
            mock(GlossaryService.class), new ApiSlots(properties));

    @Test
    @DisplayName("a changed dose and an overflowing block are both recorded with their pages")
    void recordsTranslationAndRenderFindings() {
        TextbookId textbookId = TextbookId.newId();
        Textbook textbook = Textbook.justUploaded(
                        textbookId, "Pharmacology", Optional.empty(), 12, 10,
                        ContentHash.ofText("book"), new BlobRef("ab/abc.pdf"))
                .withStatus(TextbookStatus.READY);
        Chapter chapter = new Chapter(ChapterId.newId(), textbookId, Optional.empty(), 0, 0,
                "Analgesics", PageRange.of(11, 12));

        when(pageLayouts.findRange(textbookId, PageRange.of(11, 12))).thenReturn(List.of(
                page(11, block("p11-b0", "Give 300 mg of aspirin as a loading dose.")),
                page(12, block("p12-b0", "Monitor renal function during treatment."))));
        when(memory.findAll(any(), any())).thenReturn(Map.of());
        when(model.billable()).thenReturn(false);
        when(model.name()).thenReturn("fake");
        when(model.translate(any())).thenReturn(new TranslationModelPort.Result(
                List.of(
                        // The dose is corrupted: 300 became 30.
                        new TranslatedBlock("p11-b0", "Administrați 30 mg de aspirină ca doză de încărcare."),
                        new TranslatedBlock("p12-b0", "Monitorizați funcția renală în timpul tratamentului.")),
                List.of(), TranslationModelPort.Usage.none()));
        when(renderer.mode()).thenReturn(RenderMode.OVERLAY);
        when(renderer.render(any())).thenReturn(new PdfRendererPort.RenderResult(
                new byte[]{1}, 2, List.of(new QualityFinding.Overflow("p12-b0", 12))));
        when(blobStore.storeBytes(any(), anyString())).thenReturn(
                new BlobStorePort.StoredBlob(new BlobRef("cd/out.pdf"), ContentHash.ofText("out"), 1));

        Progress progress = new Progress();
        useCase.run(command(textbookId), textbook, List.of(chapter), progress);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<JobFinding>> recorded = ArgumentCaptor.forClass(List.class);
        verify(jobs, times(2)).recordFindings(eq(progress.jobId()), recorded.capture());

        List<JobFinding> fromTranslation = recorded.getAllValues().get(0);
        assertThat(fromTranslation).singleElement().satisfies(finding -> {
            assertThat(finding.kind()).isEqualTo("NUMERIC_MISMATCH");
            assertThat(finding.pageNo()).isEqualTo(11);
            assertThat(finding.message()).contains("[300]").contains("[30]");
        });

        List<JobFinding> fromRendering = recorded.getAllValues().get(1);
        assertThat(fromRendering).singleElement().satisfies(finding -> {
            assertThat(finding.kind()).isEqualTo("OVERFLOW");
            assertThat(finding.pageNo()).isEqualTo(12);
        });
    }

    private static TextBlock block(String id, String text) {
        return new TextBlock(id, text, new BlockBox(50, 100, 400, 30),
                new FontInfo("Minion-Regular", 9.5f, false, false), 0, 0, TextRole.BODY);
    }

    private static PageLayout page(int pageNo, TextBlock block) {
        return new PageLayout(pageNo, 595, 842, PageKind.TEXT, List.of(block));
    }

    private static TranslateChaptersUseCase.Command command(TextbookId textbookId) {
        return new TranslateChaptersUseCase.Command(
                textbookId, List.of(), 0, Integer.MAX_VALUE, LanguageTag.of("ro"),
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
