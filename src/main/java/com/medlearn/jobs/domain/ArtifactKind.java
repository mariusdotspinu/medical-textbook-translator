package com.medlearn.jobs.domain;

public enum ArtifactKind {
    /** A translated chapter or chapter range. */
    TRANSLATION,
    /** A study guide for the same range, in the target language. */
    STUDY_GUIDE,
    /** Extraction diagnostics. */
    DEBUG_LAYOUT
}
