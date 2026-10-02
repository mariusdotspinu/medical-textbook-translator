package com.medlearn.jobs.domain.port;

import com.medlearn.jobs.domain.Artifact;
import com.medlearn.shared.ArtifactId;
import com.medlearn.shared.BlobRef;
import com.medlearn.shared.JobId;
import com.medlearn.shared.TextbookId;

import java.util.List;
import java.util.Optional;

public interface ArtifactRepository {

    void insert(Artifact artifact);

    Optional<Artifact> findById(ArtifactId id);

    List<Artifact> findByJob(JobId jobId);

    /** Files of every artifact produced by any job of the textbook, each listed once. */
    List<BlobRef> blobsForTextbook(TextbookId textbookId);

    /**
     * Whether any artifact still points at this file. Blobs are content-addressed, so two
     * artifacts with identical bytes share one file.
     */
    boolean isReferenced(BlobRef blob);
}
