package com.medlearn.ingestion.adapter.web;

import com.medlearn.ingestion.application.ExtractionStatsQuery;
import com.medlearn.ingestion.application.IngestTextbookUseCase;
import com.medlearn.ingestion.application.IngestTextbookUseCase.UploadOutcome;
import com.medlearn.ingestion.application.LayoutDebugService;
import com.medlearn.shared.TextbookId;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Inbound adapter for ingestion: accepting a PDF and reporting what extraction produced.
 *
 * <p>Deliberately separate from {@code TextbookController}, which serves the catalog.
 * Uploading is an ingestion concern, and keeping it here is what lets the
 * {@code ingestion -> library} dependency stay one-way - a single controller calling both
 * contexts would put a cycle between them, which
 * {@code HexagonalArchitectureTest.contextsAreAcyclic} rejects.
 *
 * <p>Both controllers map {@code /api/v1/textbooks}; they differ by HTTP method and
 * sub-path, so the API stays coherent from the outside regardless of this split.
 */
@RestController
@RequestMapping("/api/v1/textbooks")
public class IngestionController {

    private final IngestTextbookUseCase ingest;
    private final ExtractionStatsQuery extractionStats;
    private final LayoutDebugService layoutDebug;

    public IngestionController(
            IngestTextbookUseCase ingest,
            ExtractionStatsQuery extractionStats,
            LayoutDebugService layoutDebug) {
        this.ingest = ingest;
        this.extractionStats = extractionStats;
        this.layoutDebug = layoutDebug;
    }

    /**
     * Accepts a textbook and returns immediately with a job to watch.
     *
     * <p>{@code 202}, never {@code 201}: the bytes are stored and the document probed, but
     * the thousand pages are not extracted yet.
     */
    @PostMapping
    public ResponseEntity<?> upload(@RequestPart("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(new ErrorResponse("No file was uploaded"));
        }

        UploadOutcome outcome;
        try (var stream = file.getInputStream()) {
            outcome = ingest.upload(stream, file.getOriginalFilename());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the uploaded file", e);
        }

        return switch (outcome) {
            case UploadOutcome.Accepted accepted -> ResponseEntity.accepted()
                    .body(new UploadAccepted(
                            accepted.textbookId().toString(),
                            accepted.jobId().toString(),
                            "Extraction started. Watch the job for progress."));

            // 200, not 202: nothing new was started, and the caller already has what it wanted.
            case UploadOutcome.AlreadyExists existing -> ResponseEntity.ok(
                    new UploadDuplicate(
                            existing.textbookId().toString(),
                            "This file was already ingested."));

            case UploadOutcome.Rejected rejected -> ResponseEntity.unprocessableEntity()
                    .body(new ErrorResponse(rejected.error().message()));
        };
    }

    /**
     * What extraction actually produced, which is also a cheap scan check: a 1000-page
     * medical textbook reporting only a few thousand characters is images, not text.
     */
    @GetMapping("/{id}/extraction")
    public ExtractionResponse extraction(@PathVariable String id) {
        var stats = extractionStats.of(TextbookId.of(id));
        return new ExtractionResponse(
                id,
                stats.extractedPages(),
                stats.totalCharacters(),
                stats.extractedPages() == 0
                        ? 0
                        : stats.totalCharacters() / stats.extractedPages());
    }

    /**
     * Extraction, drawn over the original pages: one numbered box per block, coloured by
     * column.
     *
     * <p>The fastest way to check reading order. On a correct two-column page the numbering
     * runs down the left column and then down the right; numbering that alternates across
     * the gutter is the column-merge defect.
     */
    @GetMapping(path = "/{id}/debug/layout", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> debugLayout(
            @PathVariable String id,
            @RequestParam(defaultValue = "1") int from,
            @RequestParam(required = false) Integer to) {

        int lastPage = to == null ? from + 2 : to;
        return layoutDebug.render(TextbookId.of(id), from, lastPage)
                .map(pdf -> ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_DISPOSITION,
                                "inline; filename=\"layout-%d-%d.pdf\"".formatted(from, lastPage))
                        .contentType(MediaType.APPLICATION_PDF)
                        .body(pdf))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    record UploadAccepted(String textbookId, String jobId, String message) {}

    record UploadDuplicate(String textbookId, String message) {}

    record ErrorResponse(String error) {}

    /**
     * @param averageCharactersPerPage a text-based medical page runs several thousand;
     *                                 double digits means the pages are scans
     */
    record ExtractionResponse(
            String textbookId, int extractedPages, long totalCharacters,
            long averageCharactersPerPage) {}
}
