package com.medlearn.ingestion.application;

import com.medlearn.ingestion.domain.port.LayoutExtractorPort;
import com.medlearn.ingestion.domain.port.OutlinePort;
import com.medlearn.ingestion.domain.port.PageLayoutRepository;
import com.medlearn.jobs.application.JobCoordinator;
import com.medlearn.jobs.domain.JobType;
import com.medlearn.library.domain.Textbook;
import com.medlearn.library.domain.TextbookStatus;
import com.medlearn.library.domain.port.ChapterRepository;
import com.medlearn.library.domain.port.TextbookRepository;
import com.medlearn.platform.config.IngestionProperties;
import com.medlearn.shared.BlobRef;
import com.medlearn.shared.ContentHash;
import com.medlearn.shared.JobId;
import com.medlearn.shared.TextbookId;
import com.medlearn.shared.port.BlobStorePort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A textbook whose ingest does not finish must end FAILED, and re-uploading must retry it. */
class IngestTextbookFailureTest {

    private final BlobStorePort blobStore = mock(BlobStorePort.class);
    private final LayoutExtractorPort extractor = mock(LayoutExtractorPort.class);
    private final OutlinePort outlines = mock(OutlinePort.class);
    private final PageLayoutRepository pageLayouts = mock(PageLayoutRepository.class);
    private final TextbookRepository textbooks = mock(TextbookRepository.class);
    private final ChapterRepository chapters = mock(ChapterRepository.class);
    private final JobCoordinator jobs = mock(JobCoordinator.class);
    private final ExecutorService extractionPool = Executors.newFixedThreadPool(2);

    private final IngestTextbookUseCase ingest = new IngestTextbookUseCase(
            blobStore, extractor, outlines, pageLayouts, textbooks, chapters,
            new ChapterDetectionService(), mock(TableOfContentsDetector.class), jobs, extractionPool,
            new IngestionProperties(2, 100));

    private final TextbookId textbookId = TextbookId.newId();
    private final BlobRef blob = new BlobRef("ab/abc.pdf");

    @AfterEach
    void shutDown() {
        extractionPool.shutdownNow();
    }

    @Test
    @DisplayName("an extraction failure marks the textbook FAILED and still fails the job")
    void failureMarksTextbookFailed() {
        when(blobStore.resolveToLocalFile(blob)).thenReturn(Path.of("book.pdf"));
        when(outlines.readOutline(any(), anyInt())).thenReturn(List.of());
        when(extractor.open(any())).thenThrow(new IllegalStateException("corrupt xref table"));

        assertThatThrownBy(() -> ingest.extract(textbookId, blob, 4, new Progress(false)))
                .hasMessageContaining("corrupt xref table");

        var order = inOrder(textbooks);
        order.verify(textbooks).updateStatus(textbookId, TextbookStatus.EXTRACTING);
        order.verify(textbooks).updateStatus(textbookId, TextbookStatus.FAILED);
        verify(textbooks, never()).markReady(any(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("a cancelled ingest marks the textbook FAILED instead of leaving it EXTRACTING")
    void cancellationMarksTextbookFailed() {
        when(blobStore.resolveToLocalFile(blob)).thenReturn(Path.of("book.pdf"));
        when(outlines.readOutline(any(), anyInt())).thenReturn(List.of());
        when(extractor.open(any())).thenReturn(mock(LayoutExtractorPort.Session.class));

        ingest.extract(textbookId, blob, 4, new Progress(true));

        verify(textbooks).updateStatus(textbookId, TextbookStatus.FAILED);
        verify(textbooks, never()).markReady(any(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("re-uploading the file of a FAILED textbook retries ingestion under the same id")
    void reuploadRetriesFailedTextbook() {
        ContentHash hash = ContentHash.ofText("book");
        Textbook failed = Textbook.justUploaded(
                        textbookId, "Cardiology", Optional.empty(), 4, 10, hash, blob)
                .withStatus(TextbookStatus.FAILED);
        JobId jobId = JobId.newId();

        when(blobStore.store(any(), anyString()))
                .thenReturn(new BlobStorePort.StoredBlob(blob, hash, 10));
        when(textbooks.findByHash(hash)).thenReturn(Optional.of(failed));
        when(jobs.submit(eq(JobType.INGEST), eq(textbookId), anyString(), any())).thenReturn(jobId);

        var outcome = ingest.upload(new ByteArrayInputStream(new byte[10]), "cardiology.pdf");

        assertThat(outcome).isEqualTo(new IngestTextbookUseCase.UploadOutcome.Accepted(textbookId, jobId));
        verify(textbooks).updateStatus(textbookId, TextbookStatus.UPLOADED);
        verify(textbooks, never()).insert(any());
    }

    @Test
    @DisplayName("re-uploading the file of a READY textbook is still a no-op")
    void reuploadOfReadyTextbookIsANoOp() {
        ContentHash hash = ContentHash.ofText("book");
        Textbook ready = Textbook.justUploaded(
                        textbookId, "Cardiology", Optional.empty(), 4, 10, hash, blob)
                .withStatus(TextbookStatus.READY);

        when(blobStore.store(any(), anyString()))
                .thenReturn(new BlobStorePort.StoredBlob(blob, hash, 10));
        when(textbooks.findByHash(hash)).thenReturn(Optional.of(ready));

        var outcome = ingest.upload(new ByteArrayInputStream(new byte[10]), "cardiology.pdf");

        assertThat(outcome).isEqualTo(new IngestTextbookUseCase.UploadOutcome.AlreadyExists(textbookId));
        verify(jobs, never()).submit(any(), any(), anyString(), any());
    }

    private record Progress(boolean cancelled) implements JobCoordinator.ProgressSink {
        @Override public JobId jobId() { return JobId.newId(); }
        @Override public void total(int totalUnits) {}
        @Override public void advance(int completedUnits) {}
        @Override public void phase(String phase) {}
    }
}
