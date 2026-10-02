package com.medlearn.ingestion.domain;

public enum PageKind {
    /** Extractable text layer present. The normal path. */
    TEXT,

    /** Nearly no extractable characters but a large image - needs the vision OCR fallback. */
    SCANNED,

    /** Genuinely blank, or decorative only. Skipped by translation. */
    EMPTY
}
