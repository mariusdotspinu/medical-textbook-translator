package com.medlearn.ingestion.application;

import com.medlearn.platform.config.IngestionProperties;
import com.medlearn.ingestion.domain.HeaderFooterFilter;
import com.medlearn.ingestion.domain.OutlineNode;
import com.medlearn.ingestion.domain.PageLayout;
import com.medlearn.ingestion.domain.port.LayoutExtractorPort;
import com.medlearn.ingestion.domain.port.OutlinePort;
import com.medlearn.ingestion.domain.port.PageLayoutRepository;
import com.medlearn.jobs.application.JobCoordinator;
import com.medlearn.jobs.domain.JobType;
import com.medlearn.library.domain.ChapterSource;
import com.medlearn.library.domain.Textbook;
import com.medlearn.library.domain.TextbookStatus;
import com.medlearn.library.domain.port.ChapterRepository;
import com.medlearn.library.domain.port.TextbookRepository;
import com.medlearn.shared.BlobRef;
import com.medlearn.shared.DomainError;
import com.medlearn.shared.JobId;
import com.medlearn.shared.PageRange;
import com.medlearn.shared.TextbookId;
import com.medlearn.shared.port.BlobStorePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * Ingests an uploaded PDF: stores it, reads its structure, and extracts every page.
 *
 * <p>Upload returns as soon as the bytes are safe and the document has been probed;
 * extraction runs as a job. For a 1000-page textbook that is the difference between an
 * HTTP request that returns in a second and one that times out.
 *
 * <p>Pages are extracted in parallel over <b>contiguous</b> chunks, one chunk per worker.
 * Contiguous rather than interleaved because each worker holds its own document handle and
 * PDFBox's internal caches reward sequential access; striping pages across workers would
 * make every read a cache miss.
 */
@Service
public class IngestTextbookUseCase {

    private static final Logger log = LoggerFactory.getLogger(IngestTextbookUseCase.class);

    private final BlobStorePort blobStore;
    private final LayoutExtractorPort extractor;
    private final OutlinePort outlineReader;
    private final PageLayoutRepository pageLayouts;
    private final TextbookRepository textbooks;
    private final ChapterRepository chapters;
    private final ChapterDetectionService chapterDetection;
    private final TableOfContentsDetector tableOfContents;
    private final JobCoordinator jobs;
    private final ExecutorService extractionExecutor;
    private final IngestionProperties properties;

    public IngestTextbookUseCase(
            BlobStorePort blobStore,
            LayoutExtractorPort extractor,
            OutlinePort outlineReader,
            PageLayoutRepository pageLayouts,
            TextbookRepository textbooks,
            ChapterRepository chapters,
            ChapterDetectionService chapterDetection,
            TableOfContentsDetector tableOfContents,
            JobCoordinator jobs,
            @Qualifier("extractionExecutor") ExecutorService extractionExecutor,
            IngestionProperties properties) {
        this.blobStore = blobStore;
        this.extractor = extractor;
        this.outlineReader = outlineReader;
        this.pageLayouts = pageLayouts;
        this.textbooks = textbooks;
        this.chapters = chapters;
        this.chapterDetection = chapterDetection;
        this.tableOfContents = tableOfContents;
        this.jobs = jobs;
        this.extractionExecutor = extractionExecutor;
        this.properties = properties;
    }

    /**
     * Stores the upload and schedules extraction.
     *
     * <p>The stream is consumed once, straight to disk, hashing as it goes - it is never
     * materialised in memory.
     */
    public UploadOutcome upload(InputStream pdfStream, String originalFilename) {
        BlobStorePort.StoredBlob stored = blobStore.store(pdfStream, originalFilename);

        Optional<Textbook> existing = textbooks.findByHash(stored.hash());
        if (existing.isPresent()) {
            // Content-addressed storage means the bytes are already there; nothing to clean up.
            Textbook book = existing.get();
            if (book.status() == TextbookStatus.FAILED) {
                // Uploading the same file again is how a reader retries a failed ingest, so
                // the textbook keeps its id and is extracted again from the stored bytes.
                log.info("Upload matches failed textbook {}; retrying ingestion", book.id());
                textbooks.updateStatus(book.id(), TextbookStatus.UPLOADED);
                JobId jobId = startIngest(book.id(), book.blob(), book.pageCount());
                return new UploadOutcome.Accepted(book.id(), jobId);
            }
            log.info("Upload matches existing textbook {}", book.id());
            return new UploadOutcome.AlreadyExists(book.id());
        }

        Path localFile = blobStore.resolveToLocalFile(stored.ref());
        LayoutExtractorPort.DocumentProbe probe;
        try {
            probe = extractor.probe(localFile);
        } catch (RuntimeException e) {
            // The underlying message carries the internal blob path; log it, but never
            // return it - an API response must not disclose server filesystem layout.
            log.warn("Rejecting upload '{}' stored as {}: {}",
                    originalFilename, stored.ref(), e.getMessage());
            return reject(stored, new DomainError.NotAPdf("the file could not be parsed as a PDF"));
        }

        TextbookId textbookId = TextbookId.newId();
        if (probe.encrypted()) {
            return reject(stored, new DomainError.EncryptedPdf(textbookId));
        }
        if (probe.pageCount() == 0) {
            return reject(stored, new DomainError.NotAPdf("the document has no pages"));
        }

        Textbook textbook = Textbook.justUploaded(
                textbookId,
                chooseTitle(probe.title(), originalFilename),
                Optional.ofNullable(probe.authors()),
                probe.pageCount(),
                stored.byteSize(),
                stored.hash(),
                stored.ref());
        textbooks.insert(textbook);

        JobId jobId = startIngest(textbookId, stored.ref(), probe.pageCount());
        return new UploadOutcome.Accepted(textbookId, jobId);
    }

    private JobId startIngest(TextbookId textbookId, BlobRef blobRef, int pageCount) {
        return jobs.submit(
                JobType.INGEST, textbookId, "reading structure",
                progress -> extract(textbookId, blobRef, pageCount, progress));
    }

    /**
     * Runs inside the ingest job.
     *
     * <p>Any ending other than success marks the textbook {@code FAILED}, so it never stays
     * in {@code EXTRACTING} looking busy. Uploading the same file again then retries.
     */
    void extract(
            TextbookId textbookId,
            BlobRef blobRef,
            int pageCount,
            JobCoordinator.ProgressSink progress) {

        boolean ready = false;
        try {
            ready = extractAll(textbookId, blobRef, pageCount, progress);
        } finally {
            if (!ready) markFailed(textbookId);
        }
    }

    /** @return true if the textbook was marked ready, false if cancelled first */
    private boolean extractAll(
            TextbookId textbookId,
            BlobRef blobRef,
            int pageCount,
            JobCoordinator.ProgressSink progress) {

        textbooks.updateStatus(textbookId, TextbookStatus.EXTRACTING);
        // Two passes over the pages: extraction, then page-furniture classification.
        // Counted honestly so the progress bar does not sit at 100% while still working.
        progress.total(pageCount * 2);

        Path file = blobStore.resolveToLocalFile(blobRef);

        progress.phase("detecting chapters");
        List<OutlineNode> outline = outlineReader.readOutline(file, pageCount);
        ChapterDetectionService.DetectedChapters detected =
                chapterDetection.detect(textbookId, outline, pageCount);
        chapters.replaceAll(textbookId, detected.chapters());

        progress.phase("extracting pages");
        HeaderFooterFilter furniture = new HeaderFooterFilter();
        extractAllPages(textbookId, file, pageCount, progress, furniture);

        if (progress.cancelled()) {
            log.info("Ingest of {} cancelled before completion", textbookId);
            return false;
        }

        progress.phase("identifying running heads and page numbers");
        applyFurnitureRoles(textbookId, pageCount, furniture, progress);

        if (progress.cancelled()) {
            log.info("Ingest of {} cancelled before completion", textbookId);
            return false;
        }

        // No bookmarks: try the printed table of contents. Needs the extracted pages and the
        // recognised page numbers, which is why it runs only now.
        if (detected.source() == ChapterSource.NONE) {
            progress.phase("reading the table of contents");
            Optional<ChapterDetectionService.DetectedChapters> fromToc =
                    tableOfContents.detect(textbookId, file, pageCount, progress);
            if (progress.cancelled()) return false;
            if (fromToc.isPresent()) {
                detected = fromToc.get();
                chapters.replaceAll(textbookId, detected.chapters());
            }
        }

        long characters = pageLayouts.totalCharCount(textbookId);
        textbooks.markReady(textbookId, detected.source(), null, pageCount);
        log.info("Ingested {}: {} pages, {} chapter nodes, {} characters extracted",
                textbookId, pageCount, detected.chapters().size(), characters);
        return true;
    }

    /**
     * Marks the textbook failed without masking the original failure.
     *
     * <p>The interrupt status is cleared for the write and restored after: a cancelled job's
     * thread is interrupted, and borrowing a pooled connection on it can fail.
     */
    private void markFailed(TextbookId textbookId) {
        boolean interrupted = Thread.interrupted();
        try {
            textbooks.updateStatus(textbookId, TextbookStatus.FAILED);
        } catch (RuntimeException e) {
            log.error("Could not mark textbook {} as failed", textbookId, e);
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private void extractAllPages(
            TextbookId textbookId, Path file, int pageCount,
            JobCoordinator.ProgressSink progress, HeaderFooterFilter furniture) {

        List<PageRange> chunks = splitIntoChunks(pageCount, properties.resolvedParallelism());
        log.info("Extracting {} pages of {} across {} workers", pageCount, textbookId, chunks.size());

        List<CompletableFuture<Void>> futures = chunks.stream()
                .map(chunk -> CompletableFuture.runAsync(
                        () -> extractChunk(textbookId, file, chunk, progress, furniture),
                        extractionExecutor))
                .toList();

        // join() rethrows the first failure, which fails the job - correct, because a
        // partially extracted textbook must not be advertised as READY.
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
    }

    private void extractChunk(
            TextbookId textbookId, Path file, PageRange chunk,
            JobCoordinator.ProgressSink progress, HeaderFooterFilter furniture) {

        int batchSize = properties.pageWriteBatchSize();
        List<PageLayout> pending = new ArrayList<>(batchSize);

        // One session per worker: PDFBox's PDDocument is not thread-safe, so sharing one
        // across these workers would corrupt parses rather than merely slow them down.
        try (LayoutExtractorPort.Session session = extractor.open(file)) {
            for (int pageNo = chunk.startPage(); pageNo <= chunk.endPage(); pageNo++) {
                if (progress.cancelled()) return;

                PageLayout layout = session.extractPage(pageNo);
                // Observed here rather than in a third pass: the furniture counts come for
                // free while the page is already in hand.
                furniture.observe(layout);
                pending.add(layout);

                if (pending.size() >= batchSize) {
                    pageLayouts.saveAll(textbookId, pending);
                    progress.advance(pending.size());
                    pending.clear();
                }
            }
            if (!pending.isEmpty()) {
                pageLayouts.saveAll(textbookId, pending);
                progress.advance(pending.size());
            }
        }
    }

    /**
     * Second pass: re-roles running heads and page numbers now that repetition is known.
     *
     * <p>Pages are revisited in windows rather than all at once, so peak memory stays
     * proportional to the batch size and not to the length of the book. Only pages that
     * actually changed are written back.
     */
    private void applyFurnitureRoles(
            TextbookId textbookId, int pageCount,
            HeaderFooterFilter furniture, JobCoordinator.ProgressSink progress) {

        Set<String> repeated = furniture.repeatedKeys();
        if (repeated.isEmpty()) {
            log.info("No repeating page furniture found for {}", textbookId);
            progress.advance(pageCount);
            return;
        }

        int batchSize = properties.pageWriteBatchSize();
        int rewrittenPages = 0;

        for (int start = 1; start <= pageCount; start += batchSize) {
            if (progress.cancelled()) return;

            int end = Math.min(start + batchSize - 1, pageCount);
            List<PageLayout> window = pageLayouts.findRange(textbookId, PageRange.of(start, end));

            List<PageLayout> changed = window.stream()
                    .map(page -> furniture.apply(page, repeated))
                    // apply() returns the same instance when nothing changed, so identity
                    // comparison is exactly the right filter here.
                    .filter(updated -> !containsIdentical(window, updated))
                    .toList();

            if (!changed.isEmpty()) {
                pageLayouts.saveAll(textbookId, changed);
                rewrittenPages += changed.size();
            }
            progress.advance(end - start + 1);
        }

        log.info("Classified page furniture for {}: {} distinct repeating elements, {} pages updated",
                textbookId, repeated.size(), rewrittenPages);
    }

    private static boolean containsIdentical(List<PageLayout> original, PageLayout candidate) {
        for (PageLayout page : original) {
            if (page == candidate) return true;
        }
        return false;
    }

    /** Splits 1..pageCount into at most {@code workers} contiguous, near-equal ranges. */
    static List<PageRange> splitIntoChunks(int pageCount, int workers) {
        if (pageCount <= 0) return List.of();
        int effectiveWorkers = Math.max(1, Math.min(workers, pageCount));
        int baseSize = pageCount / effectiveWorkers;
        int remainder = pageCount % effectiveWorkers;

        List<PageRange> chunks = new ArrayList<>(effectiveWorkers);
        int next = 1;
        for (int i = 0; i < effectiveWorkers; i++) {
            // Spread the remainder over the first chunks so sizes differ by at most one.
            int size = baseSize + (i < remainder ? 1 : 0);
            chunks.add(PageRange.of(next, next + size - 1));
            next += size;
        }
        return List.copyOf(chunks);
    }

    /**
     * Rejects an upload and removes the bytes it stored.
     *
     * <p>Safe to delete unconditionally here: the duplicate check above already returned
     * for any hash an existing textbook references, so nothing else points at this blob.
     * Without this, every rejected upload would leave an orphan on disk forever.
     */
    private UploadOutcome reject(BlobStorePort.StoredBlob stored, DomainError error) {
        blobStore.delete(stored.ref());
        return new UploadOutcome.Rejected(error);
    }

    private static String chooseTitle(String embeddedTitle, String filename) {
        if (embeddedTitle != null && !embeddedTitle.isBlank()) return embeddedTitle.trim();
        String name = filename == null || filename.isBlank() ? "Untitled textbook" : filename;
        name = Path.of(name).getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** Outcome of an upload attempt - switched exhaustively by the controller. */
    public sealed interface UploadOutcome {
        record Accepted(TextbookId textbookId, JobId jobId) implements UploadOutcome {}

        /** The same bytes were already ingested; the caller should use this textbook. */
        record AlreadyExists(TextbookId textbookId) implements UploadOutcome {}

        record Rejected(DomainError error) implements UploadOutcome {}
    }
}
