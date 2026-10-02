package com.medlearn.ingestion.domain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Turns a page's raw word runs into ordered, paragraph-level translatable blocks.
 *
 * <p>Order of operations is the whole point, and it is not interchangeable:
 *
 * <ol>
 *   <li><b>Detect columns</b> from word runs, before anything is joined.
 *   <li><b>Bucket runs by baseline</b>, then decide per bucket whether it is one full-width
 *       line or two column lines sharing a baseline.
 *   <li><b>Merge paragraphs within each column</b> separately.
 *   <li><b>Order by band, then column, then position</b>.
 * </ol>
 *
 * <p>Joining text before columns are known is the defect this class exists to prevent: it
 * concatenates the left column's sentence with the right column's unrelated sentence,
 * yielding fluent nonsense that survives translation looking entirely plausible.
 *
 * <p><b>The discriminator.</b> A full-width heading is not one wide run - PDFBox reports it
 * as many narrow word runs, so asking whether a <em>run</em> crosses the gutter almost never
 * fires. The reliable test is at line level, on what sits inside the gutter: two merged
 * column lines leave the gutter channel <em>empty</em> (the left column stops before it, the
 * right starts after it), whereas a genuine full-width line has runs inside or across it.
 *
 * <p><b>Bands.</b> A full-width element - a chapter heading, a wide table, a caption running
 * the measure of the page - divides the page horizontally. Content above it is read before
 * it; both columns below are read after. Without bands, a heading halfway down the page
 * would be read either before everything above it or after everything below it.
 */
public final class PageSegmenter {

    /** A run counts as occupying the gutter if it overlaps this fraction of the channel. */
    private static final float GUTTER_OCCUPANCY_RATIO = 0.5f;

    private PageSegmenter() {}

    /**
     * @param pageNo    1-based, used to mint block ids
     * @param runs      every word run on the page
     * @param pageWidth page width in points, needed to locate the gutter
     */
    public static List<TextBlock> segment(int pageNo, List<TextRun> runs, float pageWidth) {
        return segment(pageNo, runs, pageWidth, ParagraphMerger::merge);
    }

    /**
     * Columns and reading order as {@link #segment}, but one block per visual line: no
     * paragraph merging.
     *
     * <p>For structure that lives in lines, such as a table of contents, where merging would
     * join consecutive entries into one paragraph (dot leaders make every entry full width,
     * so none looks like a paragraph's short last line).
     */
    public static List<TextBlock> segmentLines(int pageNo, List<TextRun> runs, float pageWidth) {
        return segment(pageNo, runs, pageWidth, lines -> lines);
    }

    private static List<TextBlock> segment(
            int pageNo, List<TextRun> runs, float pageWidth,
            UnaryOperator<List<TextBlock>> merge) {
        if (runs == null || runs.isEmpty()) return List.of();

        ColumnLayout layout = ColumnDetector.detect(runs, pageWidth);
        if (layout.isSingleColumn()) {
            return renumber(pageNo, merge.apply(LineGrouper.groupIntoLines(pageNo, runs)));
        }

        float medianSize = LineGrouper.medianFontSize(runs);

        List<TextBlock> spanningLines = new ArrayList<>();
        List<TextBlock> leftLines = new ArrayList<>();
        List<TextBlock> rightLines = new ArrayList<>();

        int index = 0;
        for (List<TextRun> bucket : LineGrouper.bucketByBaseline(runs)) {
            if (occupiesGutter(bucket, layout)) {
                // Genuine full-width line: assemble the bucket as one block.
                spanningLines.add(LineGrouper.assembleLine(pageNo, index++, bucket, medianSize));
                continue;
            }

            // Otherwise every run sits on one side of the gutter. Split the bucket by column
            // and assemble each side independently - this is the merge that must not happen.
            List<TextRun> left = bucket.stream().filter(run -> layout.columnOf(run) == 0).toList();
            List<TextRun> right = bucket.stream().filter(run -> layout.columnOf(run) == 1).toList();

            if (!left.isEmpty()) {
                leftLines.add(LineGrouper.assembleLine(pageNo, index++, left, medianSize).withColumn(0));
            }
            if (!right.isEmpty()) {
                rightLines.add(LineGrouper.assembleLine(pageNo, index++, right, medianSize).withColumn(1));
            }
        }

        List<TextBlock> spanBlocks = merge.apply(spanningLines);
        List<TextBlock> leftBlocks = merge.apply(leftLines);
        List<TextBlock> rightBlocks = merge.apply(rightLines);

        return renumber(pageNo, inBandOrder(spanBlocks, leftBlocks, rightBlocks));
    }

    /**
     * Whether any run of this line actually sits in the gutter channel.
     *
     * <p>True for a full-width heading or a wide table row; false for two column lines that
     * merely share a baseline, because the channel between them is blank by construction.
     */
    private static boolean occupiesGutter(List<TextRun> bucket, ColumnLayout layout) {
        float gutterWidth = layout.gutterEnd() - layout.gutterStart();
        if (gutterWidth <= 0) return false;
        float required = gutterWidth * GUTTER_OCCUPANCY_RATIO;

        for (TextRun run : bucket) {
            float overlap = Math.min(run.right(), layout.gutterEnd())
                    - Math.max(run.x(), layout.gutterStart());
            if (overlap >= required) return true;
        }
        return false;
    }

    /**
     * Sorts by {@code (band, spanning-last, column, y)}.
     *
     * <p>A block's band is the number of full-width elements above it. Spanning blocks sort
     * <em>last</em> within their own band, which places a mid-page heading after the column
     * text above it and before the column text below it.
     */
    private static List<TextBlock> inBandOrder(
            List<TextBlock> spanBlocks, List<TextBlock> leftBlocks, List<TextBlock> rightBlocks) {

        float[] spanTops = new float[spanBlocks.size()];
        for (int i = 0; i < spanBlocks.size(); i++) {
            spanTops[i] = spanBlocks.get(i).box().y();
        }
        Arrays.sort(spanTops);

        List<Ranked> ranked =
                new ArrayList<>(spanBlocks.size() + leftBlocks.size() + rightBlocks.size());
        for (TextBlock block : spanBlocks) ranked.add(rank(block, spanTops, true));
        for (TextBlock block : leftBlocks) ranked.add(rank(block, spanTops, false));
        for (TextBlock block : rightBlocks) ranked.add(rank(block, spanTops, false));

        return ranked.stream()
                .sorted(Comparator.comparingInt(Ranked::band)
                        .thenComparingInt(Ranked::spanningLast)
                        .thenComparingInt(Ranked::column)
                        .thenComparing(Ranked::top)
                        .thenComparing(Ranked::left))
                .map(Ranked::block)
                .toList();
    }

    private static Ranked rank(TextBlock block, float[] spanTops, boolean spanning) {
        float top = block.box().y();
        int band = 0;
        for (float spanTop : spanTops) {
            if (spanTop < top) band++;
        }
        return new Ranked(block, band, spanning ? 1 : 0, block.columnIndex(), top, block.box().x());
    }

    /**
     * Assigns final ids and reading-order indices.
     *
     * <p>Needed because each column is assembled independently: without this a page could
     * contain several blocks sharing the id {@code p7-b0}, and a translated block could not
     * be matched back to its source.
     */
    private static List<TextBlock> renumber(int pageNo, List<TextBlock> blocks) {
        List<TextBlock> result = new ArrayList<>(blocks.size());
        int index = 0;
        for (TextBlock block : blocks) {
            if (block.text().isBlank()) continue;
            result.add(new TextBlock(
                    TextBlock.idFor(pageNo, index),
                    block.text(),
                    block.box(),
                    block.font(),
                    block.columnIndex(),
                    index,
                    block.role()));
            index++;
        }
        return List.copyOf(result);
    }

    private record Ranked(
            TextBlock block, int band, int spanningLast, int column, float top, float left) {}
}
