package com.medlearn.jobs.domain;

import com.medlearn.shared.ArtifactId;
import com.medlearn.shared.BlobRef;
import com.medlearn.shared.JobId;

import java.time.Instant;
import java.util.Optional;

/**
 * A file a job produced.
 *
 * <p>Belongs to jobs rather than to translation because every context that generates output
 * produces one, and the download endpoint should not need to know which.
 *
 * <p>Bytes live in the blob store, not here: a translated 40-page chapter is a few megabytes
 * and has no business in a database row.
 */
public record Artifact(
        ArtifactId id,
        JobId jobId,
        ArtifactKind kind,
        BlobRef blob,
        String filename,
        long byteSize,
        Optional<Integer> pageCount,
        Instant createdAt) {

    public Artifact {
        if (id == null) throw new IllegalArgumentException("artifact id is required");
        if (jobId == null) throw new IllegalArgumentException("job id is required");
        if (kind == null) throw new IllegalArgumentException("artifact kind is required");
        if (blob == null) throw new IllegalArgumentException("blob ref is required");
        if (filename == null || filename.isBlank()) filename = kind.name().toLowerCase() + ".pdf";
        if (createdAt == null) createdAt = Instant.now();
        pageCount = pageCount == null ? Optional.empty() : pageCount;
    }

    public static Artifact of(
            JobId jobId, ArtifactKind kind, BlobRef blob,
            String filename, long byteSize, int pageCount) {
        return new Artifact(ArtifactId.newId(), jobId, kind, blob, filename, byteSize,
                Optional.of(pageCount), Instant.now());
    }
}
