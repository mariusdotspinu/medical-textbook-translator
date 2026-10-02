package com.medlearn.ingestion.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Groups positioned word runs into visual lines.
 *
 * <p>Pure domain logic - no PDF library involved - so it is unit-testable against
 * synthetic coordinates, which is how the two-column cases get pinned down.
 *
 * <p>Deliberately does <em>not</em> handle columns or paragraphs. Columns are resolved
 * before this runs (Phase 2 {@code ColumnDetector}); paragraph merging happens after. This
 * class only answers "which runs sit on the same baseline".
 */
public final class LineGrouper {

    /** Fraction of median glyph height within which two runs count as the same line. */
    private static final float LINE_TOLERANCE_RATIO = 0.5f;

    /** Fraction of a space's width above which a gap between runs becomes a real space. */
    private static final float SPACE_GAP_RATIO = 0.3f;

    /** A line this much larger than the page's median text size is treated as a heading. */
    private static final float HEADING_SIZE_RATIO = 1.3f;

    private LineGrouper() {}

    /**
     * @param pageNo 1-based, used to mint stable block ids
     * @param runs   word runs for one page, in any order
     * @return one block per visual line, in top-to-bottom then left-to-right order
     */
    public static List<TextBlock> groupIntoLines(int pageNo, List<TextRun> runs) {
        if (runs == null || runs.isEmpty()) return List.of();

        float medianSize = medianFontSize(runs);
        List<List<TextRun>> lines = bucketByBaseline(runs);

        List<TextBlock> blocks = new ArrayList<>(lines.size());
        int index = 0;
        for (List<TextRun> line : lines) {
            TextBlock block = assembleLine(pageNo, index, line, medianSize);
            if (!block.text().isBlank()) {
                blocks.add(block);
                index++;
            }
        }
        return List.copyOf(blocks);
    }

    /**
     * Buckets runs that share a baseline, without joining them into text yet.
     *
     * <p>Exposed separately because {@link PageSegmenter} has to inspect each bucket before
     * assembling it: a bucket crossing the column gutter is either a genuine full-width line
     * or two column lines that merely share a baseline, and only the run positions inside
     * the bucket distinguish the two.
     *
     * @return buckets in top-to-bottom order, each in left-to-right order
     */
    public static List<List<TextRun>> bucketByBaseline(List<TextRun> runs) {
        if (runs == null || runs.isEmpty()) return List.of();

        float medianHeight = median(runs.stream().map(TextRun::height).sorted().toList());
        float tolerance = Math.max(1.0f, medianHeight * LINE_TOLERANCE_RATIO);

        List<TextRun> sorted = runs.stream()
                .sorted(Comparator.comparing(TextRun::centerY).thenComparing(TextRun::x))
                .toList();

        List<List<TextRun>> lines = new ArrayList<>();
        List<TextRun> current = new ArrayList<>();
        float currentCenterY = sorted.getFirst().centerY();

        for (TextRun run : sorted) {
            if (current.isEmpty() || Math.abs(run.centerY() - currentCenterY) <= tolerance) {
                current.add(run);
                // Running mean keeps the reference stable on lines with mixed glyph heights
                // (superscripts, small caps) instead of letting the first run define it.
                currentCenterY = (currentCenterY * (current.size() - 1) + run.centerY()) / current.size();
            } else {
                lines.add(List.copyOf(current));
                current = new ArrayList<>(List.of(run));
                currentCenterY = run.centerY();
            }
        }
        if (!current.isEmpty()) lines.add(List.copyOf(current));
        return List.copyOf(lines);
    }

    /** Median font size on the page, the reference for heading detection. */
    public static float medianFontSize(List<TextRun> runs) {
        return median(runs.stream().map(TextRun::fontSizePt).sorted().toList());
    }

    static TextBlock assembleLine(int pageNo, int index, List<TextRun> line, float medianSize) {
        List<TextRun> ordered = line.stream().sorted(Comparator.comparing(TextRun::x)).toList();

        StringBuilder text = new StringBuilder();
        BlockBox box = ordered.getFirst().box();
        TextRun previous = null;

        for (TextRun run : ordered) {
            if (previous != null) {
                float gap = run.x() - previous.right();
                float spaceWidth = previous.spaceWidth() > 0 ? previous.spaceWidth() : previous.height() * 0.25f;
                boolean needsSpace = gap > spaceWidth * SPACE_GAP_RATIO
                        && !text.isEmpty()
                        && text.charAt(text.length() - 1) != ' ';
                if (needsSpace) text.append(' ');
            }
            text.append(run.text());
            box = box.union(run.box());
            previous = run;
        }

        FontInfo font = dominantFont(ordered);
        TextRole role = font.sizePt() > medianSize * HEADING_SIZE_RATIO ? TextRole.HEADING : TextRole.BODY;

        return new TextBlock(
                TextBlock.idFor(pageNo, index),
                normalizeWhitespace(text.toString()),
                box,
                font,
                0,
                index,
                role);
    }

    /** The face covering the most characters, so a stray italic word cannot define the line. */
    private static FontInfo dominantFont(List<TextRun> runs) {
        Map<String, Integer> charsByFont = new HashMap<>();
        Map<String, TextRun> exemplar = new HashMap<>();
        for (TextRun run : runs) {
            String key = run.fontName() + '@' + run.fontSizePt();
            charsByFont.merge(key, run.text().length(), Integer::sum);
            exemplar.putIfAbsent(key, run);
        }
        TextRun winner = charsByFont.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(e -> exemplar.get(e.getKey()))
                .orElse(runs.getFirst());
        return FontInfo.of(winner.fontName(), winner.fontSizePt());
    }

    private static String normalizeWhitespace(String raw) {
        return raw.replaceAll("\\s+", " ").trim();
    }

    private static float median(List<Float> sortedValues) {
        if (sortedValues.isEmpty()) return 0f;
        int middle = sortedValues.size() / 2;
        return sortedValues.size() % 2 == 1
                ? sortedValues.get(middle)
                : (sortedValues.get(middle - 1) + sortedValues.get(middle)) / 2f;
    }
}
