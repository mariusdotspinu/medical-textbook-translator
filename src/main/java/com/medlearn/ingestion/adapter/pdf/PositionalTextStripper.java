package com.medlearn.ingestion.adapter.pdf;

import com.medlearn.ingestion.domain.TextRun;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Captures positioned text runs instead of producing a plain string.
 *
 * <p>{@code PDFTextStripper} calls {@link #writeString} once per word, which is exactly
 * the granularity wanted: line and paragraph assembly is done by
 * {@link com.medlearn.ingestion.domain.LineGrouper} so that columns can be resolved first.
 *
 * <p><b>Not thread-safe</b>, in keeping with PDFBox. One instance belongs to one
 * extraction session on one thread.
 */
final class PositionalTextStripper extends PDFTextStripper {

    private final List<TextRun> runs = new ArrayList<>();

    PositionalTextStripper() throws IOException {
        // Gives runs in roughly reading order. It does not solve columns - PDFBox sorts by
        // position without knowing where the gutter is - but it makes the input to line
        // grouping stable.
        setSortByPosition(true);
    }

    @Override
    protected void writeString(String text, List<TextPosition> textPositions) {
        if (text == null || text.isBlank() || textPositions.isEmpty()) return;

        float left = Float.MAX_VALUE;
        float top = Float.MAX_VALUE;
        float right = -Float.MAX_VALUE;
        float bottom = -Float.MAX_VALUE;

        for (TextPosition position : textPositions) {
            float x = position.getXDirAdj();
            float height = position.getHeightDir();
            // getYDirAdj() is measured from the top of the page down to the glyph's
            // baseline, so the box's top edge sits one glyph-height above it.
            float glyphTop = position.getYDirAdj() - height;

            left = Math.min(left, x);
            top = Math.min(top, glyphTop);
            right = Math.max(right, x + position.getWidthDirAdj());
            bottom = Math.max(bottom, glyphTop + height);
        }

        TextPosition first = textPositions.getFirst();
        runs.add(new TextRun(
                text,
                left,
                top,
                Math.max(0f, right - left),
                Math.max(0f, bottom - top),
                fontNameOf(first.getFont()),
                first.getFontSizeInPt(),
                first.getWidthOfSpace()));
    }

    /** Broken documents can carry text positions with no resolvable font. */
    private static String fontNameOf(PDFont font) {
        if (font == null) return "unknown";
        try {
            String name = font.getName();
            return name == null || name.isBlank() ? "unknown" : name;
        } catch (RuntimeException e) {
            return "unknown";
        }
    }

    List<TextRun> capturedRuns() {
        return List.copyOf(runs);
    }

    void reset() {
        runs.clear();
    }
}
