package com.medlearn.maintenance.application;

import com.medlearn.shared.BlobRef;
import com.medlearn.shared.port.BlobReferencesPort;
import com.medlearn.shared.port.BlobStorePort;
import com.medlearn.shared.port.BlobStorePort.BlobEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Finds and deletes stored files that no row points at any more.
 *
 * <p>Such files come from deletes made before deletion cleaned up artifact files, and from
 * crashes between writing a file and inserting the row that references it. They are never
 * read again, so they only take up disk space.
 *
 * <p>Two safety rules:
 * <ul>
 *   <li><b>A grace period.</b> Files written in the last {@link #GRACE} are kept even when
 *       unreferenced. Every write stores its file first and inserts its row immediately
 *       after, so a recent unreferenced file may be an upload or artifact whose row is about
 *       to appear.
 *   <li><b>Refuse without references.</b> If no context reports its references, every file
 *       would look orphaned; that is a wiring bug, not an empty library.
 * </ul>
 *
 * <p>Not run automatically. A blob directory shared with a different database (say, an
 * override of {@code SPRING_DATASOURCE_URL}) would make every file look orphaned, so
 * deleting is an explicit request made after looking at {@link #preview()}.
 */
@Service
public class OrphanBlobSweeper {

    private static final Logger log = LoggerFactory.getLogger(OrphanBlobSweeper.class);

    static final Duration GRACE = Duration.ofHours(1);

    private final BlobStorePort blobStore;
    private final List<BlobReferencesPort> references;
    private final Clock clock;

    @Autowired
    public OrphanBlobSweeper(BlobStorePort blobStore, List<BlobReferencesPort> references) {
        this(blobStore, references, Clock.systemUTC());
    }

    OrphanBlobSweeper(BlobStorePort blobStore, List<BlobReferencesPort> references, Clock clock) {
        this.blobStore = blobStore;
        this.references = List.copyOf(references);
        this.clock = clock;
    }

    /** What {@link #sweep()} would delete right now, without deleting anything. */
    public SweepReport preview() {
        return plan();
    }

    /** Deletes what {@link #preview()} reports. */
    public SweepReport sweep() {
        SweepReport report = plan();
        report.orphans().forEach(entry -> blobStore.delete(entry.ref()));
        report.incompleteWrites().forEach(entry -> blobStore.delete(entry.ref()));
        log.info("Swept {} orphaned file(s) and {} incomplete write(s), {} KB; "
                        + "kept {} recent unreferenced file(s)",
                report.orphans().size(), report.incompleteWrites().size(), report.totalBytes() / 1024,
                report.recentUnreferencedKept());
        return report;
    }

    private SweepReport plan() {
        if (references.isEmpty()) {
            throw new IllegalStateException(
                    "No BlobReferencesPort is configured; refusing to treat every file as orphaned");
        }

        Instant cutoff = clock.instant().minus(GRACE);
        Set<BlobRef> referenced = new HashSet<>();
        references.forEach(port -> referenced.addAll(port.referencedBlobs()));

        List<BlobEntry> unreferenced = blobStore.list().stream()
                .filter(entry -> !referenced.contains(entry.ref()))
                .toList();
        List<BlobEntry> orphans = unreferenced.stream()
                .filter(entry -> entry.modifiedAt().isBefore(cutoff))
                .toList();
        List<BlobEntry> incompleteWrites = blobStore.listIncompleteWrites().stream()
                .filter(entry -> entry.modifiedAt().isBefore(cutoff))
                .toList();

        return new SweepReport(orphans, incompleteWrites, unreferenced.size() - orphans.size());
    }

    /**
     * @param orphans                  finished files nothing references, older than the grace period
     * @param incompleteWrites         temporary files of writes that never completed, older than it
     * @param recentUnreferencedKept   unreferenced files kept because they are too new
     */
    public record SweepReport(
            List<BlobEntry> orphans, List<BlobEntry> incompleteWrites, int recentUnreferencedKept) {

        public long totalBytes() {
            return orphans.stream().mapToLong(BlobEntry::byteSize).sum()
                    + incompleteWrites.stream().mapToLong(BlobEntry::byteSize).sum();
        }
    }
}
