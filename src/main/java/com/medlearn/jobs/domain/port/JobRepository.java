package com.medlearn.jobs.domain.port;

import com.medlearn.jobs.domain.Job;
import com.medlearn.jobs.domain.JobFinding;
import com.medlearn.jobs.domain.JobState;
import com.medlearn.shared.JobId;
import com.medlearn.shared.TextbookId;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface JobRepository {

    void insert(Job job);

    Optional<Job> findById(JobId id);

    List<Job> findByTextbook(TextbookId textbookId);

    /** Persists a state transition, including timestamps and any error text. */
    void updateState(JobId id, JobState state);

    /**
     * Progress-only update.
     *
     * <p>Separate from {@link #updateState} because it fires frequently - once per page
     * group or request batch - and must stay a single cheap UPDATE rather than rewriting
     * the whole row.
     */
    void updateProgress(JobId id, int doneUnits, int totalUnits, String phase);

    /**
     * Fails every job still {@code PENDING} or {@code RUNNING}.
     *
     * @return how many jobs were failed
     */
    int failUnfinished(String message, Instant finishedAt);

    /**
     * Deletes every job of a textbook; their artifact rows cascade.
     *
     * @return how many jobs were deleted
     */
    int deleteByTextbook(TextbookId textbookId);

    /** Adds findings to the job's existing ones; called once per stage that produces them. */
    void appendFindings(JobId id, List<JobFinding> findings);

    /** All findings of a job, in the order they were recorded. */
    List<JobFinding> findFindings(JobId id);
}
