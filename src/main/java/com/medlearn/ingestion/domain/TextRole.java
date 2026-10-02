package com.medlearn.ingestion.domain;

/**
 * What a block is for, which decides whether it gets translated at all.
 *
 * <p>Running heads and folios repeat on hundreds of pages. Translating them would waste
 * tokens, and re-rendering them risks disturbing the page furniture the reader navigates by,
 * so they are detected once and skipped.
 */
public enum TextRole {

    BODY(true),
    HEADING(true),
    CAPTION(true),
    FOOTNOTE(true),

    /** Repeated chapter/section title at the top of the page. */
    RUNNING_HEAD(false),

    /** Page number. */
    FOLIO(false),

    /** Bibliography entries: author names and journal titles must survive verbatim. */
    REFERENCE(false);

    private final boolean translatable;

    TextRole(boolean translatable) {
        this.translatable = translatable;
    }

    public boolean translatable() {
        return translatable;
    }
}
