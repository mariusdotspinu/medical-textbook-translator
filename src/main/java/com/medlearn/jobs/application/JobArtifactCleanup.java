package com.medlearn.jobs.application;

import com.medlearn.jobs.domain.port.ArtifactRepository;
import com.medlearn.jobs.domain.port.JobRepository;
import com.medlearn.library.domain.port.TextbookDependents;
import com.medlearn.shared.BlobRef;
import com.medlearn.shared.TextbookId;
import com.medlearn.shared.port.BlobStorePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Removes a textbook's jobs and the artifact files they produced when the textbook is deleted.
 *
 * <p>Without this the artifact rows cascade away with the textbook but their PDFs stay in the
 * blob store forever, with nothing pointing at them.
 *
 * <p>Files are deleted only after the rows are gone, and only when no remaining artifact
 * references them: blobs are content-addressed, so an identical PDF produced for another
 * textbook shares the same file. A failure part-way leaves an orphaned file rather than a row
 * pointing at nothing.
 */
@Component
class JobArtifactCleanup implements TextbookDependents {

    private static final Logger log = LoggerFactory.getLogger(JobArtifactCleanup.class);

    private final JobCoordinator coordinator;
    private final JobRepository jobs;
    private final ArtifactRepository artifacts;
    private final BlobStorePort blobStore;

    JobArtifactCleanup(
            JobCoordinator coordinator,
            JobRepository jobs,
            ArtifactRepository artifacts,
            BlobStorePort blobStore) {
        this.coordinator = coordinator;
        this.jobs = jobs;
        this.artifacts = artifacts;
        this.blobStore = blobStore;
    }

    @Override
    public boolean busy(TextbookId textbookId) {
        return coordinator.hasRunningJob(textbookId);
    }

    @Override
    public void remove(TextbookId textbookId) {
        List<BlobRef> files = artifacts.blobsForTextbook(textbookId);
        int deletedJobs = jobs.deleteByTextbook(textbookId);

        int deletedFiles = 0;
        for (BlobRef file : files) {
            if (artifacts.isReferenced(file)) continue;   // shared with another textbook's artifact
            blobStore.delete(file);
            deletedFiles++;
        }
        log.info("Removed {} job(s) and {} artifact file(s) of textbook {}",
                deletedJobs, deletedFiles, textbookId);
    }
}
