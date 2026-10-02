package com.medlearn.maintenance.application;

import com.medlearn.shared.BlobRef;
import com.medlearn.shared.port.BlobReferencesPort;
import com.medlearn.shared.port.BlobStorePort;
import com.medlearn.shared.port.BlobStorePort.BlobEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrphanBlobSweeperTest {

    private static final Instant NOW = Instant.parse("2026-10-02T12:00:00Z");
    private static final Instant OLD = NOW.minus(Duration.ofDays(3));
    private static final Instant RECENT = NOW.minus(Duration.ofMinutes(5));

    private final BlobStorePort blobStore = mock(BlobStorePort.class);
    private final BlobReferencesPort textbooks = () -> Set.of(new BlobRef("aa/original.pdf"));
    private final BlobReferencesPort artifacts = () -> Set.of(new BlobRef("bb/translation.pdf"));
    private final OrphanBlobSweeper sweeper = new OrphanBlobSweeper(
            blobStore, List.of(textbooks, artifacts), Clock.fixed(NOW, ZoneOffset.UTC));

    private final BlobEntry original = entry("aa/original.pdf", OLD);
    private final BlobEntry translation = entry("bb/translation.pdf", OLD);
    private final BlobEntry oldOrphan = entry("cc/deleted-before-the-fix.pdf", OLD);
    private final BlobEntry freshUpload = entry("dd/row-not-written-yet.pdf", RECENT);
    private final BlobEntry crashedUpload = entry("incoming/blob-1.part", OLD);
    private final BlobEntry uploadInProgress = entry("incoming/blob-2.part", RECENT);

    @Test
    @DisplayName("only old files that no context references are deleted")
    void sweepsOldUnreferencedFilesOnly() {
        when(blobStore.list()).thenReturn(List.of(original, translation, oldOrphan, freshUpload));
        when(blobStore.listIncompleteWrites()).thenReturn(List.of(crashedUpload, uploadInProgress));

        OrphanBlobSweeper.SweepReport report = sweeper.sweep();

        assertThat(report.orphans()).containsExactly(oldOrphan);
        assertThat(report.incompleteWrites()).containsExactly(crashedUpload);
        assertThat(report.recentUnreferencedKept()).isEqualTo(1);
        verify(blobStore).delete(oldOrphan.ref());
        verify(blobStore).delete(crashedUpload.ref());
        verify(blobStore, never()).delete(original.ref());
        verify(blobStore, never()).delete(translation.ref());
        verify(blobStore, never()).delete(freshUpload.ref());
        verify(blobStore, never()).delete(uploadInProgress.ref());
    }

    @Test
    @DisplayName("a preview reports the same files and deletes nothing")
    void previewDeletesNothing() {
        when(blobStore.list()).thenReturn(List.of(original, oldOrphan));
        when(blobStore.listIncompleteWrites()).thenReturn(List.of());

        assertThat(sweeper.preview().orphans()).containsExactly(oldOrphan);
        verify(blobStore, never()).delete(any());
    }

    @Test
    @DisplayName("with no reference sources, nothing is treated as orphaned")
    void refusesWithoutReferenceSources() {
        OrphanBlobSweeper unwired = new OrphanBlobSweeper(
                blobStore, List.of(), Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(unwired::sweep).isInstanceOf(IllegalStateException.class);
        verify(blobStore, never()).delete(any());
    }

    private static BlobEntry entry(String key, Instant modifiedAt) {
        return new BlobEntry(new BlobRef(key), 1024, modifiedAt);
    }
}
