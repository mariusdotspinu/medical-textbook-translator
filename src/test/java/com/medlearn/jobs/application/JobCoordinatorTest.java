package com.medlearn.jobs.application;

import com.medlearn.jobs.domain.Job;
import com.medlearn.jobs.domain.JobFinding;
import com.medlearn.jobs.domain.JobState;
import com.medlearn.jobs.domain.JobType;
import com.medlearn.jobs.domain.port.JobRepository;
import com.medlearn.shared.JobId;
import com.medlearn.shared.TextbookId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class JobCoordinatorTest {

    private final InMemoryJobs jobs = new InMemoryJobs();
    private final ExecutorService jobExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final ExecutorService workerPool = Executors.newFixedThreadPool(2);
    private final JobCoordinator coordinator =
            new JobCoordinator(jobs, new JobProgressBroker(), jobExecutor);

    @AfterEach
    void shutDown() {
        jobExecutor.shutdownNow();
        workerPool.shutdownNow();
    }

    @Test
    @DisplayName("cancellation is visible to work running on another executor's threads")
    void cancellationReachesWorkerThreads() throws Exception {
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch workerSawCancel = new CountDownLatch(1);

        JobId jobId = coordinator.submit(JobType.INGEST, TextbookId.newId(), "testing", progress -> {
            // The shape of extraction and translation: the job thread waits on work that
            // runs elsewhere, and only the job thread is interrupted by a cancel.
            Future<?> worker = workerPool.submit(() -> {
                workerStarted.countDown();
                while (!progress.cancelled()) Thread.onSpinWait();
                workerSawCancel.countDown();
            });
            worker.get();
        });

        assertThat(workerStarted.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(coordinator.cancel(jobId)).isTrue();

        assertThat(workerSawCancel.await(5, TimeUnit.SECONDS)).isTrue();
        awaitFinished(jobId);
        assertThat(jobs.state(jobId)).isInstanceOf(JobState.Cancelled.class);
    }

    @Test
    @DisplayName("work that throws because it was cancelled ends CANCELLED, not FAILED")
    void failureAfterCancelIsReportedAsCancelled() throws Exception {
        CountDownLatch started = new CountDownLatch(1);

        JobId jobId = coordinator.submit(JobType.TRANSLATE, TextbookId.newId(), "testing", progress -> {
            started.countDown();
            while (!progress.cancelled()) Thread.onSpinWait();
            throw new IllegalStateException("request aborted");
        });

        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        coordinator.cancel(jobId);

        awaitFinished(jobId);
        assertThat(jobs.state(jobId)).isInstanceOf(JobState.Cancelled.class);
    }

    @Test
    @DisplayName("progress reported after a cancel is not written over the cancelled state")
    void noProgressWritesAfterCancel() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);

        JobId jobId = coordinator.submit(JobType.INGEST, TextbookId.newId(), "testing", progress -> {
            progress.total(10);
            started.countDown();
            // Like a worker thread, this wait ignores the interrupt a cancel delivers.
            while (cancelled.getCount() > 0) {
                try {
                    cancelled.await();
                } catch (InterruptedException ignored) {
                    // keep waiting
                }
            }
            progress.phase("still winding down");
            progress.advance(10);
        });

        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        coordinator.cancel(jobId);
        int writesAtCancel = jobs.progressWrites.get();
        cancelled.countDown();

        awaitFinished(jobId);
        assertThat(jobs.progressWrites.get()).isEqualTo(writesAtCancel);
        assertThat(jobs.state(jobId)).isInstanceOf(JobState.Cancelled.class);
    }

    @Test
    @DisplayName("a job that finishes normally still succeeds")
    void uncancelledWorkSucceeds() throws Exception {
        JobId jobId = coordinator.submit(JobType.INGEST, TextbookId.newId(), "testing", progress -> {
            progress.total(1);
            progress.advance(1);
        });

        awaitFinished(jobId);
        assertThat(jobs.state(jobId)).isInstanceOf(JobState.Succeeded.class);
        assertThat(coordinator.cancel(jobId)).isFalse();
    }

    @Test
    @DisplayName("a textbook has a running job only while that job executes")
    void tracksRunningJobsPerTextbook() throws Exception {
        TextbookId busyBook = TextbookId.newId();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        JobId jobId = coordinator.submit(JobType.TRANSLATE, busyBook, "testing", progress -> {
            started.countDown();
            release.await();
        });

        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(coordinator.hasRunningJob(busyBook)).isTrue();
        assertThat(coordinator.hasRunningJob(TextbookId.newId())).isFalse();

        release.countDown();
        awaitFinished(jobId);
        assertThat(coordinator.hasRunningJob(busyBook)).isFalse();
    }

    @Test
    @DisplayName("jobs left unfinished by a previous process are failed at startup")
    void failsOrphanedJobsAtStartup() {
        coordinator.afterSingletonsInstantiated();

        assertThat(jobs.failUnfinishedMessage).isEqualTo(JobCoordinator.INTERRUPTED_BY_RESTART);
    }

    /** Waits for the job thread itself to finish, not just for the first terminal write. */
    private void awaitFinished(JobId jobId) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (!coordinator.running(jobId) && jobs.state(jobId).terminal()) return;
            Thread.sleep(10);
        }
        throw new AssertionError("job " + jobId + " did not finish");
    }

    private static final class InMemoryJobs implements JobRepository {

        private final Map<JobId, JobState> states = new ConcurrentHashMap<>();
        private final AtomicInteger progressWrites = new AtomicInteger();
        private volatile String failUnfinishedMessage;

        JobState state(JobId id) {
            return states.get(id);
        }

        @Override
        public void insert(Job job) {
            states.put(job.id(), job.state());
        }

        @Override
        public Optional<Job> findById(JobId id) {
            return Optional.empty();
        }

        @Override
        public List<Job> findByTextbook(TextbookId textbookId) {
            return List.of();
        }

        @Override
        public void updateState(JobId id, JobState state) {
            states.put(id, state);
        }

        @Override
        public void updateProgress(JobId id, int doneUnits, int totalUnits, String phase) {
            progressWrites.incrementAndGet();
        }

        @Override
        public int failUnfinished(String message, Instant finishedAt) {
            failUnfinishedMessage = message;
            return 0;
        }

        @Override
        public int deleteByTextbook(TextbookId textbookId) {
            return 0;
        }

        @Override
        public void appendFindings(JobId id, List<JobFinding> findings) {}

        @Override
        public List<JobFinding> findFindings(JobId id) {
            return List.of();
        }
    }
}
