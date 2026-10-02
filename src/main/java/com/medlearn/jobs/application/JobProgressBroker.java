package com.medlearn.jobs.application;

import com.medlearn.jobs.domain.JobState;
import com.medlearn.shared.JobId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Fans job progress out to browser clients over SSE.
 *
 * <p>In-memory and therefore single-instance: a browser watching a job must be connected to
 * the node running it. That is correct for the local, single-user target; scaling out would
 * replace this with a shared bus behind the same two methods, which is the reason the rest
 * of the code only ever calls {@link #publish}.
 *
 * <p>A dead or slow client must never affect the job, so every delivery failure simply
 * unsubscribes that emitter.
 */
@Component
public class JobProgressBroker {

    private static final Logger log = LoggerFactory.getLogger(JobProgressBroker.class);
    private static final long EMITTER_TIMEOUT_MILLIS = 30 * 60 * 1000L;

    private final Map<JobId, List<SseEmitter>> subscribers = new ConcurrentHashMap<>();

    /**
     * Subscribes to a job's progress, replaying its current state first.
     *
     * <p>Replaying matters: a client that connects a moment after the job finished would
     * otherwise sit on a silent stream until the timeout, having missed the only event it
     * cared about. An already-terminal job gets one event and an immediate close.
     *
     * <p>Sending before the emitter is returned to Spring MVC is safe - {@code SseEmitter}
     * buffers sends made before initialisation.
     */
    public SseEmitter subscribe(JobId jobId, JobState currentState) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MILLIS);

        try {
            emitter.send(SseEmitter.event().name("progress")
                    .data(ProgressEvent.from(jobId, currentState)));
        } catch (IOException | IllegalStateException e) {
            log.debug("Client for job {} disconnected before the first event", jobId);
            return emitter;
        }

        if (currentState.terminal()) {
            emitter.complete();
            return emitter;
        }

        subscribers.computeIfAbsent(jobId, id -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> remove(jobId, emitter));
        emitter.onTimeout(() -> remove(jobId, emitter));
        emitter.onError(error -> remove(jobId, emitter));
        return emitter;
    }

    public void publish(JobId jobId, JobState state) {
        List<SseEmitter> emitters = subscribers.get(jobId);
        if (emitters == null || emitters.isEmpty()) return;

        ProgressEvent event = ProgressEvent.from(jobId, state);
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name("progress").data(event));
            } catch (IOException | IllegalStateException e) {
                // Client went away mid-send; drop it rather than letting the job notice.
                remove(jobId, emitter);
            }
        }

        if (state.terminal()) closeAll(jobId);
    }

    private void closeAll(JobId jobId) {
        List<SseEmitter> emitters = subscribers.remove(jobId);
        if (emitters == null) return;
        for (SseEmitter emitter : emitters) {
            try {
                emitter.complete();
            } catch (RuntimeException e) {
                log.debug("Emitter for job {} already closed", jobId);
            }
        }
    }

    private void remove(JobId jobId, SseEmitter emitter) {
        List<SseEmitter> emitters = subscribers.get(jobId);
        if (emitters != null) {
            emitters.remove(emitter);
            if (emitters.isEmpty()) subscribers.remove(jobId);
        }
    }

    /**
     * Wire format for a progress event.
     *
     * @param percent -1 while the total is still unknown, so the UI can show an
     *                indeterminate bar instead of a misleading 0%
     */
    public record ProgressEvent(
            String jobId, String state, String phase, int doneUnits, int totalUnits,
            int percent, String error) {

        static ProgressEvent from(JobId jobId, JobState state) {
            return switch (state) {
                case JobState.Pending ignored ->
                        new ProgressEvent(jobId.toString(), "PENDING", "queued", 0, 0, -1, null);
                case JobState.Running running -> new ProgressEvent(
                        jobId.toString(), "RUNNING", running.phase(),
                        running.doneUnits(), running.totalUnits(),
                        running.totalUnits() == 0 ? -1 : running.percent(), null);
                case JobState.Succeeded succeeded -> new ProgressEvent(
                        jobId.toString(), "SUCCEEDED", "done",
                        succeeded.totalUnits(), succeeded.totalUnits(), 100, null);
                case JobState.Failed failed -> new ProgressEvent(
                        jobId.toString(), "FAILED", "failed", 0, 0, -1, failed.message());
                case JobState.Cancelled ignored ->
                        new ProgressEvent(jobId.toString(), "CANCELLED", "cancelled", 0, 0, -1, null);
            };
        }
    }
}
