package com.medlearn.jobs.application;

import com.medlearn.jobs.domain.port.ArtifactRepository;
import com.medlearn.jobs.domain.port.JobRepository;
import com.medlearn.shared.BlobRef;
import com.medlearn.shared.TextbookId;
import com.medlearn.shared.port.BlobStorePort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JobArtifactCleanupTest {

    private final JobCoordinator coordinator = mock(JobCoordinator.class);
    private final JobRepository jobs = mock(JobRepository.class);
    private final ArtifactRepository artifacts = mock(ArtifactRepository.class);
    private final BlobStorePort blobStore = mock(BlobStorePort.class);
    private final JobArtifactCleanup cleanup =
            new JobArtifactCleanup(coordinator, jobs, artifacts, blobStore);

    private final TextbookId textbookId = TextbookId.newId();

    @Test
    @DisplayName("artifact files are deleted after their rows, except files another artifact shares")
    void deletesUnsharedArtifactFiles() {
        BlobRef onlyHere = new BlobRef("aa/translation.pdf");
        BlobRef shared = new BlobRef("bb/identical-guide.pdf");
        when(artifacts.blobsForTextbook(textbookId)).thenReturn(List.of(onlyHere, shared));
        when(artifacts.isReferenced(onlyHere)).thenReturn(false);
        when(artifacts.isReferenced(shared)).thenReturn(true);

        cleanup.remove(textbookId);

        var order = inOrder(jobs, artifacts, blobStore);
        order.verify(jobs).deleteByTextbook(textbookId);
        order.verify(artifacts).isReferenced(onlyHere);
        order.verify(blobStore).delete(onlyHere);
        verify(blobStore, never()).delete(shared);
    }

    @Test
    @DisplayName("a textbook is busy while one of its jobs is running")
    void busyFollowsRunningJobs() {
        when(coordinator.hasRunningJob(textbookId)).thenReturn(true);

        assertThat(cleanup.busy(textbookId)).isTrue();
    }
}
