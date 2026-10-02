package com.medlearn.jobs.application;

import com.medlearn.jobs.domain.Job;
import com.medlearn.jobs.domain.JobFinding;
import com.medlearn.jobs.domain.JobSpec;
import com.medlearn.jobs.domain.JobState;
import com.medlearn.jobs.domain.JobType;
import com.medlearn.jobs.domain.port.JobRepository;
import com.medlearn.shared.JobId;
import com.medlearn.shared.TextbookId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * Runs long work off the request thread and keeps its progress durable and observable.
 *
 * <p>Uploading a 1000-page textbook or translating eight chapters takes minutes, so every
 * such endpoint returns {@code 202} with a job id immediately. State lives in the database
 * rather than only in memory, so progress survives a restart.
 *
 * <p>Jobs are not resumed after a restart: any job still unfinished at startup is marked
 * failed, since the process that was running it is gone. Re-running is cheap for
 * translations because the translation memory already holds every finished block.
 */
@Component
public class JobCoordinator implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(JobCoordinator.class);

    static final String INTERRUPTED_BY_RESTART = "interrupted by an application restart";

    private final JobRepository jobs;
    private final JobProgressBroker broker;
    private final ExecutorService jobExecutor;
    private final Map<JobId, ThrottledProgressSink> inFlight = new ConcurrentHashMap<>();

    public JobCoordinator(
            JobRepository jobs,
            JobProgressBroker broker,
            @Qualifier("jobExecutor") ExecutorService jobExecutor) {
        this.jobs = jobs;
        this.broker = broker;
        this.jobExecutor = jobExecutor;
    }

    /**
     * Fails jobs orphaned by the previous process.
     *
     * <p>Runs once every bean exists but before the web server accepts requests, so no job
     * of this process can have been submitted yet: everything unfinished is an orphan.
     */
    @Override
    public void afterSingletonsInstantiated() {
        int orphaned = jobs.failUnfinished(INTERRUPTED_BY_RESTART, Instant.now());
        if (orphaned > 0) {
            log.warn("Marked {} job(s) left unfinished by the previous run as failed", orphaned);
        }
    }

    /**
     * Records a job and starts it on a virtual thread.
     *
     * @return the job id, immediately - before the work has made any progress
     */
    public JobId submit(JobType type, TextbookId textbookId, String initialPhase, Work work) {
        return submit(JobSpec.of(type, textbookId), initialPhase, work);
    }

    /**
     * Records a job, with what it is about, and starts it on a virtual thread.
     *
     * @return the job id, immediately - before the work has made any progress
     */
    public JobId submit(JobSpec spec, String initialPhase, Work work) {
        JobId jobId = JobId.newId();
        TextbookId textbookId = spec.textbookId();
        jobs.insert(Job.pending(jobId, spec));

        // Registered before submitting, so a cancel arriving at any point finds the job, and
        // so a job finishing instantly cannot remove itself before it was ever added.
        ThrottledProgressSink sink = new ThrottledProgressSink(jobId, textbookId);
        inFlight.put(jobId, sink);
        sink.attach(jobExecutor.submit(() -> execute(sink, initialPhase, work)));
        return jobId;
    }

    private void execute(ThrottledProgressSink sink, String initialPhase, Work work) {
        JobId jobId = sink.jobId();
        try {
            if (sink.cancelled()) return;      // cancelled before it ever started

            transition(jobId, new JobState.Running(0, 0, initialPhase));
            work.execute(sink);

            if (sink.finish()) {
                sink.flush();
                transition(jobId, new JobState.Succeeded(Instant.now(), sink.total()));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            if (sink.finish()) {
                // Logged with the stack trace here; the message alone reaches the client.
                log.error("Job {} failed", jobId, e);
                transition(jobId, new JobState.Failed(Instant.now(), describe(e)));
            } else {
                // Expected: interrupting a blocking call surfaces as an exception.
                log.info("Job {} stopped after cancellation: {}", jobId, describe(e));
            }
        } finally {
            inFlight.remove(jobId);
            if (!sink.finished() && sink.cancelled()) {
                // Written again now the work has really stopped. cancel() already reported
                // the job as cancelled, but a RUNNING transition racing it could have
                // overwritten that in the meantime. The interrupt is cleared for the write:
                // borrowing a pooled connection on an interrupted thread can fail.
                boolean interrupted = Thread.interrupted();
                try {
                    transition(jobId, new JobState.Cancelled(Instant.now()));
                } finally {
                    if (interrupted) Thread.currentThread().interrupt();
                }
            }
        }
    }

    /**
     * Requests cancellation.
     *
     * <p>Sets a flag that every thread working for the job sees through
     * {@link ProgressSink#cancelled()}, including extraction workers and translation batches
     * on other executors, and interrupts the job thread so a blocking call on it returns.
     *
     * @return false if the job is unknown or already finished
     */
    public boolean cancel(JobId jobId) {
        ThrottledProgressSink sink = inFlight.get(jobId);
        if (sink == null || !sink.requestCancel()) return false;
        transition(jobId, new JobState.Cancelled(Instant.now()));
        return true;
    }

    /** Whether any job for the textbook is still executing in this process. */
    public boolean hasRunningJob(TextbookId textbookId) {
        return inFlight.values().stream().anyMatch(sink -> sink.textbookId().equals(textbookId));
    }

    /** Whether the job's work is still executing in this process. */
    boolean running(JobId jobId) {
        return inFlight.containsKey(jobId);
    }

    public Optional<Job> find(JobId jobId) {
        return jobs.findById(jobId);
    }

    /**
     * Keeps findings with the job, so problems a reader must check reach the API and UI
     * instead of only the log. Safe to call more than once; findings accumulate.
     */
    public void recordFindings(JobId jobId, List<JobFinding> findings) {
        if (findings.isEmpty()) return;
        jobs.appendFindings(jobId, findings);
    }

    public List<JobFinding> findings(JobId jobId) {
        return jobs.findFindings(jobId);
    }

    private void transition(JobId jobId, JobState state) {
        jobs.updateState(jobId, state);
        broker.publish(jobId, state);
    }

    /** Exception messages are often null (NPE); fall back to the type name. */
    private static String describe(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank()
                ? e.getClass().getSimpleName()
                : "%s: %s".formatted(e.getClass().getSimpleName(), message);
    }

    @FunctionalInterface
    public interface Work {
        void execute(ProgressSink progress) throws Exception;
    }

    /** What running work reports back. Implementations must be safe for concurrent use. */
    public interface ProgressSink {

        /**
         * The job this work belongs to.
         *
         * <p>Exposed because work that produces artifacts has to attach them to its job, and
         * the sink is the only handle it is given.
         */
        JobId jobId();

        void total(int totalUnits);

        void advance(int completedUnits);

        void phase(String phase);

        /**
         * Whether cancellation was requested. Long loops should check this so cancellation
         * takes effect promptly; it is valid from any thread doing work for the job.
         */
        boolean cancelled();
    }

    /**
     * Rate-limits progress writes.
     *
     * <p>A 1000-page ingest would otherwise issue 1000 UPDATEs and 1000 SSE frames, which
     * would cost more than the extraction itself. Updates are coalesced to one every
     * {@value #MIN_WRITE_INTERVAL_MILLIS}ms, with phase changes and the final state always
     * flushed so nothing important is swallowed.
     */
    private final class ThrottledProgressSink implements ProgressSink {

        private static final long MIN_WRITE_INTERVAL_MILLIS = 300;

        private final JobId jobId;
        private final TextbookId textbookId;
        private volatile int totalUnits;
        private volatile int doneUnits;
        private volatile String phase = "working";
        private volatile long lastWriteAt;
        private volatile boolean cancelRequested;
        private volatile Future<?> future;
        /** Set once the outcome is decided; the job can no longer be cancelled. Guarded by this. */
        private boolean finished;

        private ThrottledProgressSink(JobId jobId, TextbookId textbookId) {
            this.jobId = jobId;
            this.textbookId = textbookId;
        }

        TextbookId textbookId() {
            return textbookId;
        }

        void attach(Future<?> future) {
            this.future = future;
            // A cancel that arrived between registration and submission had no future to
            // interrupt yet.
            if (cancelRequested) future.cancel(true);
        }

        /** @return false if the job already finished, in which case nothing changes */
        synchronized boolean requestCancel() {
            if (finished) return false;
            cancelRequested = true;
            Future<?> running = future;
            if (running != null) running.cancel(true);
            return true;
        }

        /**
         * Decides the outcome as finished rather than cancelled.
         *
         * <p>Mutually exclusive with {@link #requestCancel()}, so a cancel arriving just as
         * the work completes either wins entirely or is refused, and a succeeded job can
         * never be flipped to cancelled afterwards.
         *
         * @return false if cancellation was already requested
         */
        synchronized boolean finish() {
            if (cancelRequested) return false;
            finished = true;
            return true;
        }

        synchronized boolean finished() {
            return finished;
        }

        @Override
        public JobId jobId() {
            return jobId;
        }

        @Override
        public void total(int units) {
            this.totalUnits = Math.max(0, units);
            flush();
        }

        @Override
        public synchronized void advance(int completedUnits) {
            doneUnits += Math.max(0, completedUnits);
            long now = System.currentTimeMillis();
            boolean finished = totalUnits > 0 && doneUnits >= totalUnits;
            if (finished || now - lastWriteAt >= MIN_WRITE_INTERVAL_MILLIS) {
                write(now);
            }
        }

        @Override
        public void phase(String newPhase) {
            this.phase = newPhase == null || newPhase.isBlank() ? "working" : newPhase;
            flush();
        }

        @Override
        public boolean cancelled() {
            // The flag, not the interrupt status of the calling thread: work fans out to
            // extraction workers and batch threads, and only the job thread is interrupted.
            return cancelRequested || Thread.currentThread().isInterrupted();
        }

        int total() {
            return totalUnits;
        }

        void flush() {
            write(System.currentTimeMillis());
        }

        private void write(long now) {
            // After a cancel the job is already reported as cancelled; workers still winding
            // down must not publish RUNNING over it.
            if (cancelRequested) return;
            lastWriteAt = now;
            JobState.Running state = new JobState.Running(doneUnits, totalUnits, phase);
            jobs.updateProgress(jobId, doneUnits, totalUnits, phase);
            broker.publish(jobId, state);
        }
    }
}
