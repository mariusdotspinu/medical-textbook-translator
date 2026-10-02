package com.medlearn.jobs.domain;

import java.time.Instant;

/**
 * Lifecycle of a long-running job.
 *
 * <p>Sealed, so every place that renders or reacts to a job state must switch
 * exhaustively. Adding a state later becomes a compile error at each site rather than a
 * silently missed branch - the failure mode that leaves a progress bar stuck forever.
 *
 * <p>Each variant carries only what that state actually has: there is no "error" field on a
 * running job and no progress counter on a failed one.
 */
public sealed interface JobState {

    /** Stable name persisted in the {@code job.state} column. */
    String persistedName();

    /** Whether the job has stopped for good. */
    boolean terminal();

    record Pending() implements JobState {
        @Override public String persistedName() { return "PENDING"; }
        @Override public boolean terminal() { return false; }
    }

    /**
     * @param phase human-readable current step, surfaced over SSE so a 30-minute job shows
     *              what it is doing rather than only a number
     */
    record Running(int doneUnits, int totalUnits, String phase) implements JobState {
        public Running {
            if (doneUnits < 0) doneUnits = 0;
            if (totalUnits < 0) totalUnits = 0;
            if (phase == null || phase.isBlank()) phase = "working";
        }

        @Override public String persistedName() { return "RUNNING"; }
        @Override public boolean terminal() { return false; }

        public int percent() {
            return totalUnits == 0 ? 0 : (int) Math.min(100, Math.round(100.0 * doneUnits / totalUnits));
        }
    }

    record Succeeded(Instant finishedAt, int totalUnits) implements JobState {
        @Override public String persistedName() { return "SUCCEEDED"; }
        @Override public boolean terminal() { return true; }
    }

    record Failed(Instant finishedAt, String message) implements JobState {
        public Failed {
            if (message == null || message.isBlank()) message = "unknown failure";
        }

        @Override public String persistedName() { return "FAILED"; }
        @Override public boolean terminal() { return true; }
    }

    record Cancelled(Instant finishedAt) implements JobState {
        @Override public String persistedName() { return "CANCELLED"; }
        @Override public boolean terminal() { return true; }
    }
}
