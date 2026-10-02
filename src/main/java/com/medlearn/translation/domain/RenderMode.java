package com.medlearn.translation.domain;

/**
 * How a translated chapter is turned back into a PDF.
 *
 * <p>Both modes exist because they fail differently and neither is right for every page.
 */
public enum RenderMode {

    /**
     * Keeps the original page and replaces only the words: figures, tables, diagrams and
     * page furniture are untouched, and the output stays vector and searchable.
     *
     * <p>Its failure mode is space. Romance and Slavic translations run 15-30% longer than
     * English, so text that filled its box in the source may not fit. The renderer works
     * through a fixed ladder - rewrap, shrink, tighten leading - and falls back to
     * {@link #REFLOW} for any page that still cannot be made to fit.
     */
    OVERLAY,

    /**
     * Typesets a clean new document from the translated text, with figures referenced by
     * page. Never overflows and always readable, but the original layout is gone.
     */
    REFLOW
}
