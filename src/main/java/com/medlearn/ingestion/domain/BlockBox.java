package com.medlearn.ingestion.domain;

/**
 * Bounding box of a text block on a page, in PDF points.
 *
 * <p><b>Coordinate system: top-left origin, y grows downward.</b> This matches what
 * {@code PDFTextStripper} reports via {@code TextPosition.getYDirAdj()}, which is how the
 * blocks are captured during extraction.
 *
 * <p>PDF content streams use the opposite convention - bottom-left origin, y growing
 * upward - so anything drawing into a {@code PDPageContentStream} must convert. That
 * conversion lives in exactly one place ({@code toPdfSpaceY}) because silently mixing the
 * two is the classic PDFBox defect: text lands mirrored vertically and nothing errors.
 *
 * @param x      left edge, points from the left of the page
 * @param y      top edge, points from the <em>top</em> of the page
 * @param width  horizontal extent in points
 * @param height vertical extent in points
 */
public record BlockBox(float x, float y, float width, float height) {

    public BlockBox {
        if (width < 0 || height < 0) {
            throw new IllegalArgumentException(
                    "box cannot have negative extent: %fx%f".formatted(width, height));
        }
    }

    public float right() {
        return x + width;
    }

    public float bottom() {
        return y + height;
    }

    public float centerX() {
        return x + width / 2f;
    }

    public float centerY() {
        return y + height / 2f;
    }

    public float area() {
        return width * height;
    }

    /**
     * Converts this box's top edge into the bottom-left-origin y that PDF drawing needs.
     *
     * @param pageHeight page height in points
     * @return y of this box's <em>bottom</em> edge measured from the page bottom
     */
    public float toPdfSpaceY(float pageHeight) {
        return pageHeight - bottom();
    }

    /** Smallest box containing both. Used when merging lines into a paragraph block. */
    public BlockBox union(BlockBox other) {
        float left = Math.min(x, other.x);
        float top = Math.min(y, other.y);
        float right = Math.max(right(), other.right());
        float bottom = Math.max(bottom(), other.bottom());
        return new BlockBox(left, top, right - left, bottom - top);
    }

    public boolean verticallyOverlaps(BlockBox other) {
        return y < other.bottom() && other.y < bottom();
    }

    public boolean horizontallyOverlaps(BlockBox other) {
        return x < other.right() && other.x < right();
    }
}
