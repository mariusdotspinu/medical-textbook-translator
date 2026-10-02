package com.medlearn.ingestion.domain;

/**
 * A single positioned run of text as the PDF content stream emitted it - in practice one
 * word.
 *
 * <p>Extraction captures at word granularity rather than taking PDFBox's own line
 * assembly, because line and paragraph grouping has to be done our way: a two-column
 * medical page needs columns resolved <em>before</em> lines are joined, or the two columns
 * interleave into nonsense. Owning the grouping is what makes that possible.
 *
 * @param text       the run's characters
 * @param x          left edge, points from page left
 * @param y          <b>top</b> edge, points from page top (see {@link BlockBox})
 * @param width      advance width in points
 * @param height     glyph height in points
 * @param spaceWidth width of a space in this run's font, used to decide whether adjacent
 *                   runs are separated by a space or merely kerned
 */
public record TextRun(
        String text,
        float x,
        float y,
        float width,
        float height,
        String fontName,
        float fontSizePt,
        float spaceWidth) {

    public TextRun {
        if (text == null) throw new IllegalArgumentException("run text is required");
    }

    public float right() {
        return x + width;
    }

    public float centerY() {
        return y + height / 2f;
    }

    public BlockBox box() {
        return new BlockBox(x, y, width, height);
    }
}
