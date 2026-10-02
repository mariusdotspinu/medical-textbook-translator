package com.medlearn.shared;

import java.util.UUID;

/** Identity of an ingested textbook. */
public record TextbookId(UUID value) {
    public TextbookId {
        if (value == null) throw new IllegalArgumentException("TextbookId value is required");
    }

    public static TextbookId newId() {
        return new TextbookId(UUID.randomUUID());
    }

    public static TextbookId of(String raw) {
        return new TextbookId(UUID.fromString(raw));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
