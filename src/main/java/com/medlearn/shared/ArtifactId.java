package com.medlearn.shared;

import java.util.UUID;

/** Identity of a generated artifact (translated PDF, study guide, debug render). */
public record ArtifactId(UUID value) {
    public ArtifactId {
        if (value == null) throw new IllegalArgumentException("ArtifactId value is required");
    }

    public static ArtifactId newId() {
        return new ArtifactId(UUID.randomUUID());
    }

    public static ArtifactId of(String raw) {
        return new ArtifactId(UUID.fromString(raw));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
