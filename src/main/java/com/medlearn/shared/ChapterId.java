package com.medlearn.shared;

import java.util.UUID;

/** Identity of a chapter (or any node of the chapter tree). */
public record ChapterId(UUID value) {
    public ChapterId {
        if (value == null) throw new IllegalArgumentException("ChapterId value is required");
    }

    public static ChapterId newId() {
        return new ChapterId(UUID.randomUUID());
    }

    public static ChapterId of(String raw) {
        return new ChapterId(UUID.fromString(raw));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
