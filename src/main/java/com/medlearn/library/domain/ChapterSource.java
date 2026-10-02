package com.medlearn.library.domain;

/**
 * Where a textbook's chapter structure came from, in descending reliability.
 *
 * <p>Surfaced in the API because it tells the reader how much to trust the chapter list:
 * {@code OUTLINE} is authored metadata, {@code HEURISTIC} is a guess.
 */
public enum ChapterSource {
    /** The PDF's own bookmark tree. Authoritative, and what medical textbooks usually ship. */
    OUTLINE,
    /** Parsed from the printed table of contents. */
    TOC,
    /** Inferred from font-size outliers and chapter-heading patterns. */
    HEURISTIC,
    /** No structure found; the book is treated as a single span. */
    NONE
}
