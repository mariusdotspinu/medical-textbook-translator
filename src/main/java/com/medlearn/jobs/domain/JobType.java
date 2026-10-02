package com.medlearn.jobs.domain;

public enum JobType {
    /** Extract structure and layout from an uploaded PDF. */
    INGEST,
    /** Translate a chapter range and render a PDF. */
    TRANSLATE,
    /** Generate a study guide for a chapter range. */
    STUDY_GUIDE
}
