package com.medlearn.library.domain;

public enum TextbookStatus {
    /** Bytes stored, nothing extracted yet. */
    UPLOADED,
    /** Extraction in progress; chapters and layouts are incomplete. */
    EXTRACTING,
    /** Fully extracted and available for translation. */
    READY,
    /** Extraction failed; see the ingest job for the reason. */
    FAILED
}
