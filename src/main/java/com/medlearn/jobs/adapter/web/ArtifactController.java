package com.medlearn.jobs.adapter.web;

import com.medlearn.jobs.domain.Artifact;
import com.medlearn.jobs.domain.port.ArtifactRepository;
import com.medlearn.shared.ArtifactId;
import com.medlearn.shared.JobId;
import com.medlearn.shared.port.BlobStorePort;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class ArtifactController {

    private final ArtifactRepository artifacts;
    private final BlobStorePort blobStore;

    public ArtifactController(ArtifactRepository artifacts, BlobStorePort blobStore) {
        this.artifacts = artifacts;
        this.blobStore = blobStore;
    }

    @GetMapping("/jobs/{jobId}/artifacts")
    public List<ArtifactResponse> forJob(@PathVariable String jobId) {
        return artifacts.findByJob(JobId.of(jobId)).stream().map(ArtifactResponse::from).toList();
    }

    /** Streamed, not buffered: a translated chapter can be tens of megabytes. */
    @GetMapping("/artifacts/{id}/download")
    public ResponseEntity<Resource> download(@PathVariable String id) {
        return artifacts.findById(ArtifactId.of(id))
                .map(artifact -> ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_DISPOSITION,
                                "attachment; filename=\"%s\"".formatted(artifact.filename()))
                        .contentType(MediaType.APPLICATION_PDF)
                        .contentLength(artifact.byteSize())
                        .body((Resource) new InputStreamResource(blobStore.open(artifact.blob()))))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    record ArtifactResponse(
            String id, String kind, String filename, long byteSize,
            Integer pageCount, String downloadUrl, String createdAt) {

        static ArtifactResponse from(Artifact artifact) {
            return new ArtifactResponse(
                    artifact.id().toString(),
                    artifact.kind().name(),
                    artifact.filename(),
                    artifact.byteSize(),
                    artifact.pageCount().orElse(null),
                    "/api/v1/artifacts/%s/download".formatted(artifact.id()),
                    artifact.createdAt().toString());
        }
    }
}
