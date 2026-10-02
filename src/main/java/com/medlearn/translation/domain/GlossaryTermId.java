package com.medlearn.translation.domain;

import java.util.UUID;

public record GlossaryTermId(UUID value) {

    public GlossaryTermId {
        if (value == null) throw new IllegalArgumentException("glossary term id is required");
    }

    public static GlossaryTermId newId() {
        return new GlossaryTermId(UUID.randomUUID());
    }

    public static GlossaryTermId of(String text) {
        return new GlossaryTermId(UUID.fromString(text));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
