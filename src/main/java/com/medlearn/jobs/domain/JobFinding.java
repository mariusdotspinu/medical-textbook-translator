package com.medlearn.jobs.domain;

/**
 * Something in a job's output a reader should check, kept with the job.
 *
 * <p>Deliberately generic: {@code jobs} knows nothing about translation, so the context that
 * produces findings maps its own types into this shape. That keeps the dependency one-way
 * ({@code translation -> jobs}).
 *
 * @param kind    stable machine-readable type, e.g. {@code NUMERIC_MISMATCH}
 * @param blockId the block the finding is about, e.g. {@code p412-b7}
 * @param pageNo  page in the source PDF, which is how a reader finds the passage
 * @param message human-readable description
 */
public record JobFinding(String kind, String blockId, int pageNo, String message) {

    public JobFinding {
        if (kind == null || kind.isBlank()) throw new IllegalArgumentException("finding kind is required");
        if (message == null) message = "";
    }
}
