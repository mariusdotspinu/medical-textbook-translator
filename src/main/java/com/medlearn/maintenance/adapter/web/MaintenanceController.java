package com.medlearn.maintenance.adapter.web;

import com.medlearn.maintenance.application.OrphanBlobSweeper;
import com.medlearn.maintenance.application.OrphanBlobSweeper.SweepReport;
import com.medlearn.shared.port.BlobStorePort.BlobEntry;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Stream;

/**
 * Housekeeping that is run on request rather than automatically.
 *
 * <p>{@code GET} previews and {@code DELETE} removes, so what is about to be deleted can always
 * be checked first.
 */
@RestController
@RequestMapping("/api/v1/maintenance/orphan-blobs")
public class MaintenanceController {

    private final OrphanBlobSweeper sweeper;

    public MaintenanceController(OrphanBlobSweeper sweeper) {
        this.sweeper = sweeper;
    }

    @GetMapping
    public SweepResponse preview() {
        return SweepResponse.from(sweeper.preview(), false);
    }

    @DeleteMapping
    public SweepResponse sweep() {
        return SweepResponse.from(sweeper.sweep(), true);
    }

    /**
     * @param deleted                  false for a preview, true once the files are gone
     * @param recentUnreferencedKept   unreferenced files younger than the grace period, kept
     *                                 because their row may be about to be written
     */
    record SweepResponse(
            boolean deleted, int fileCount, long totalBytes, int recentUnreferencedKept,
            List<FileResponse> files) {

        static SweepResponse from(SweepReport report, boolean deleted) {
            List<FileResponse> files = Stream.concat(
                            report.orphans().stream().map(entry -> FileResponse.from(entry, "ORPHAN")),
                            report.incompleteWrites().stream()
                                    .map(entry -> FileResponse.from(entry, "INCOMPLETE_WRITE")))
                    .toList();
            return new SweepResponse(
                    deleted, files.size(), report.totalBytes(), report.recentUnreferencedKept(), files);
        }
    }

    record FileResponse(String key, String reason, long byteSize, String modifiedAt) {
        static FileResponse from(BlobEntry entry, String reason) {
            return new FileResponse(
                    entry.ref().key(), reason, entry.byteSize(), entry.modifiedAt().toString());
        }
    }
}
