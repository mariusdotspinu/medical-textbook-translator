package com.medlearn.jobs.adapter.web;

import com.medlearn.jobs.application.JobCoordinator;
import com.medlearn.jobs.application.JobProgressBroker;
import com.medlearn.jobs.domain.Job;
import com.medlearn.jobs.domain.JobFinding;
import com.medlearn.jobs.domain.JobState;
import com.medlearn.shared.JobId;
import com.medlearn.shared.LanguageTag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/jobs")
public class JobController {

    private final JobCoordinator jobs;
    private final JobProgressBroker broker;

    public JobController(JobCoordinator jobs, JobProgressBroker broker) {
        this.jobs = jobs;
        this.broker = broker;
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable String id) {
        return jobs.find(JobId.of(id))
                .<ResponseEntity<?>>map(job -> ResponseEntity.ok(JobResponse.from(job)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(new ErrorResponse("No job with id " + id)));
    }

    /** Live progress. Polling {@code GET /{id}} works too, but this avoids the busy loop. */
    @GetMapping(path = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> events(@PathVariable String id) {
        JobId jobId = JobId.of(id);
        return jobs.find(jobId)
                .map(job -> ResponseEntity.ok(broker.subscribe(jobId, job.state())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Everything the job flagged for a reader to check, in book order.
     *
     * <p>Separate from {@code GET /{id}} because a long translation can record thousands,
     * and the job itself is polled; it carries only {@code findingCount}.
     */
    @GetMapping("/{id}/findings")
    public ResponseEntity<?> findings(@PathVariable String id) {
        JobId jobId = JobId.of(id);
        if (jobs.find(jobId).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ErrorResponse("No job with id " + id));
        }
        return ResponseEntity.ok(jobs.findings(jobId).stream().map(FindingResponse::from).toList());
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<?> cancel(@PathVariable String id) {
        JobId jobId = JobId.of(id);
        if (jobs.find(jobId).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ErrorResponse("No job with id " + id));
        }
        boolean cancelled = jobs.cancel(jobId);
        return cancelled
                ? ResponseEntity.accepted().body(new CancelResponse(id, "Cancellation requested"))
                : ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(new ErrorResponse("Job is no longer running"));
    }

    record ErrorResponse(String error) {}

    record CancelResponse(String jobId, String message) {}

    record FindingResponse(String kind, String blockId, int pageNo, String message) {
        static FindingResponse from(JobFinding finding) {
            return new FindingResponse(
                    finding.kind(), finding.blockId(), finding.pageNo(), finding.message());
        }
    }

    /**
     * @param percent -1 while the total is unknown, so the UI shows an indeterminate bar
     *                rather than a misleading 0%
     */
    record JobResponse(
            String id, String type, String state, String phase, int doneUnits, int totalUnits,
            int percent, String error, int findingCount, String textbookId,
            String targetLanguage, List<String> chapterIds, Map<String, String> options,
            String createdAt, String startedAt, String finishedAt) {

        static JobResponse from(Job job) {
            JobState state = job.state();
            Progress progress = progressOf(state);
            return new JobResponse(
                    job.id().toString(),
                    job.type().name(),
                    state.persistedName(),
                    progress.phase(),
                    progress.done(),
                    progress.total(),
                    progress.percent(),
                    progress.error(),
                    job.findingCount(),
                    job.textbookId().map(Object::toString).orElse(null),
                    job.targetLanguage().map(LanguageTag::tag).orElse(null),
                    job.chapterIds().stream().map(Object::toString).toList(),
                    job.options(),
                    job.createdAt().toString(),
                    job.startedAt().map(Object::toString).orElse(null),
                    job.finishedAt().map(Object::toString).orElse(null));
        }

        /** Exhaustive over {@link JobState}, so a new state cannot be silently unrendered. */
        private static Progress progressOf(JobState state) {
            return switch (state) {
                case JobState.Pending ignored -> new Progress("queued", 0, 0, -1, null);
                case JobState.Running running -> new Progress(
                        running.phase(), running.doneUnits(), running.totalUnits(),
                        running.totalUnits() == 0 ? -1 : running.percent(), null);
                case JobState.Succeeded succeeded -> new Progress(
                        "done", succeeded.totalUnits(), succeeded.totalUnits(), 100, null);
                case JobState.Failed failed -> new Progress("failed", 0, 0, -1, failed.message());
                case JobState.Cancelled ignored -> new Progress("cancelled", 0, 0, -1, null);
            };
        }

        private record Progress(String phase, int done, int total, int percent, String error) {}
    }
}
