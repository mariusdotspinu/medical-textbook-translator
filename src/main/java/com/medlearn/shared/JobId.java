package com.medlearn.shared;

import java.util.UUID;

/** Identity of a long-running job. */
public record JobId(UUID value) {
    public JobId {
        if (value == null) throw new IllegalArgumentException("JobId value is required");
    }

    public static JobId newId() {
        return new JobId(UUID.randomUUID());
    }

    public static JobId of(String raw) {
        return new JobId(UUID.fromString(raw));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
