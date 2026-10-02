package com.medlearn.ingestion.domain;

/**
 * The column structure of one page.
 *
 * <p>Single-column pages use {@link #singleColumn()}, which reports one column and places
 * every run in it, so callers need no special case.
 *
 * @param columnCount 1 or 2 - the only layouts medical textbooks use in practice
 * @param gutterStart left edge of the whitespace channel between columns, in points
 * @param gutterEnd   right edge of that channel
 */
public record ColumnLayout(int columnCount, float gutterStart, float gutterEnd) {

    /**
     * A run must overhang the gutter by at least this much on both sides before it counts
     * as spanning, rather than merely poking into the channel.
     */
    private static final float SPAN_MARGIN_PT = 2f;

    public ColumnLayout {
        if (columnCount < 1) throw new IllegalArgumentException("columnCount must be >= 1");
        if (columnCount > 1 && gutterEnd <= gutterStart) {
            throw new IllegalArgumentException("a multi-column layout needs a positive gutter");
        }
    }

    public static ColumnLayout singleColumn() {
        return new ColumnLayout(1, 0f, 0f);
    }

    public static ColumnLayout twoColumn(float gutterStart, float gutterEnd) {
        return new ColumnLayout(2, gutterStart, gutterEnd);
    }

    public boolean isSingleColumn() {
        return columnCount == 1;
    }

    public float gutterCenter() {
        return (gutterStart + gutterEnd) / 2f;
    }

    /**
     * Whether a run crosses the gutter, marking it as full-measure content - a chapter
     * heading, a wide table, a caption running the width of the page.
     *
     * <p>Such content divides the page into vertical bands, and reading order must respect
     * that: everything above a full-width heading is read before it, and both columns below
     * it are read after.
     */
    public boolean spansColumns(TextRun run) {
        if (isSingleColumn()) return false;
        return run.x() < gutterStart - SPAN_MARGIN_PT && run.right() > gutterEnd + SPAN_MARGIN_PT;
    }

    /** Column index for a non-spanning run, by which side of the gutter it sits on. */
    public int columnOf(TextRun run) {
        if (isSingleColumn()) return 0;
        float center = (run.x() + run.right()) / 2f;
        return center <= gutterCenter() ? 0 : 1;
    }
}
