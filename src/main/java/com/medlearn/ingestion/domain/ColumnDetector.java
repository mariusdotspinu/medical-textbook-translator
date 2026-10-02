package com.medlearn.ingestion.domain;

import java.util.Arrays;
import java.util.List;

/**
 * Finds the whitespace gutter separating text columns on a page.
 *
 * <p>This is the highest-value piece of extraction logic in the pipeline. Medical textbooks
 * are overwhelmingly two-column, and without column detection the lines of the left and
 * right columns share a baseline and get joined - producing text that reads fluently and
 * means nothing. Translating that is worse than not translating it, because the output
 * looks correct.
 *
 * <p><b>Method.</b> Horizontal coverage is histogrammed from word runs: for each x bin, how
 * many runs overlap it. On a two-column page the channel between columns has near-zero
 * coverage over the full page height, margins are zero at the edges, and body text keeps
 * coverage high everywhere else.
 *
 * <p>Coverage is compared against the page's own median rather than against zero, because a
 * full-width chapter heading legitimately crosses the gutter. Demanding exactly zero would
 * let a single heading hide the gutter for the whole page; a low-fraction threshold
 * tolerates a few crossing runs while dense body text never qualifies.
 *
 * <p>Only one gutter (two columns) is detected. Three-column layouts occur in journals but
 * not in these textbooks; such a page degrades to single-column, which merges lines rather
 * than losing them.
 */
public final class ColumnDetector {

    /** Histogram resolution: fine enough to locate a gutter edge, coarse enough to be cheap. */
    private static final float BIN_WIDTH_PT = 2f;

    /** Narrower channels are inter-word or inter-cell space, not a column gutter. */
    private static final float MIN_GUTTER_WIDTH_PT = 8f;

    /** A gutter sits in the middle of the page, never in a margin. */
    private static final float MIN_CENTER_FRACTION = 0.25f;
    private static final float MAX_CENTER_FRACTION = 0.75f;

    /** Coverage at or below this fraction of the page median counts as empty. */
    private static final float EMPTY_COVERAGE_RATIO = 0.12f;

    /** Both sides of a real gutter carry substantial text. */
    private static final float SIDE_COVERAGE_RATIO = 0.35f;

    /** Too few runs to conclude anything; one column is the safe answer. */
    private static final int MIN_RUNS_FOR_DETECTION = 25;

    /** Each column must hold at least this share of the page's runs to be believed. */
    private static final float MIN_COLUMN_SHARE = 0.15f;

    private ColumnDetector() {}

    public static ColumnLayout detect(List<TextRun> runs, float pageWidth) {
        if (runs == null || runs.size() < MIN_RUNS_FOR_DETECTION || pageWidth <= 0) {
            return ColumnLayout.singleColumn();
        }

        int binCount = (int) Math.ceil(pageWidth / BIN_WIDTH_PT);
        if (binCount < 10) return ColumnLayout.singleColumn();

        int[] coverage = histogram(runs, pageWidth, binCount);
        float median = medianOfOccupiedBins(coverage);
        if (median <= 0) return ColumnLayout.singleColumn();

        float emptyThreshold = Math.max(0f, median * EMPTY_COVERAGE_RATIO);
        float sideThreshold = median * SIDE_COVERAGE_RATIO;

        Gap widest = widestInteriorGap(coverage, binCount, emptyThreshold, sideThreshold, pageWidth);
        if (widest == null) return ColumnLayout.singleColumn();

        ColumnLayout candidate = ColumnLayout.twoColumn(widest.startPt(), widest.endPt());
        return plausible(candidate, runs) ? candidate : ColumnLayout.singleColumn();
    }

    private static int[] histogram(List<TextRun> runs, float pageWidth, int binCount) {
        int[] coverage = new int[binCount];
        for (TextRun run : runs) {
            if (run.text().isBlank()) continue;
            int from = binIndex(run.x(), pageWidth, binCount);
            int to = binIndex(run.right(), pageWidth, binCount);
            for (int bin = from; bin <= to; bin++) {
                coverage[bin]++;
            }
        }
        return coverage;
    }

    private static int binIndex(float x, float pageWidth, int binCount) {
        int index = (int) (Math.max(0f, Math.min(x, pageWidth - 0.01f)) / BIN_WIDTH_PT);
        return Math.max(0, Math.min(index, binCount - 1));
    }

    /** Median over bins containing text, so margins do not drag the baseline to zero. */
    private static float medianOfOccupiedBins(int[] coverage) {
        int[] occupied = Arrays.stream(coverage).filter(value -> value > 0).sorted().toArray();
        if (occupied.length == 0) return 0f;
        int middle = occupied.length / 2;
        return occupied.length % 2 == 1
                ? occupied[middle]
                : (occupied[middle - 1] + occupied[middle]) / 2f;
    }

    /**
     * Widest low-coverage band with substantial text on both sides, sitting mid-page. The
     * side test is what distinguishes a gutter from a margin.
     */
    private static Gap widestInteriorGap(
            int[] coverage, int binCount, float emptyThreshold, float sideThreshold, float pageWidth) {

        Gap best = null;
        int runStart = -1;

        for (int bin = 0; bin <= binCount; bin++) {
            boolean empty = bin < binCount && coverage[bin] <= emptyThreshold;

            if (empty && runStart < 0) {
                runStart = bin;
            } else if (!empty && runStart >= 0) {
                Gap gap = evaluate(coverage, runStart, bin - 1, sideThreshold, pageWidth, binCount);
                if (gap != null && (best == null || gap.widthPt() > best.widthPt())) best = gap;
                runStart = -1;
            }
        }
        return best;
    }

    private static Gap evaluate(
            int[] coverage, int firstBin, int lastBin,
            float sideThreshold, float pageWidth, int binCount) {

        float startPt = firstBin * BIN_WIDTH_PT;
        float endPt = (lastBin + 1) * BIN_WIDTH_PT;
        if (endPt - startPt < MIN_GUTTER_WIDTH_PT) return null;

        float centerFraction = ((startPt + endPt) / 2f) / pageWidth;
        if (centerFraction < MIN_CENTER_FRACTION || centerFraction > MAX_CENTER_FRACTION) return null;

        if (!hasCoverage(coverage, 0, firstBin - 1, sideThreshold)) return null;
        if (!hasCoverage(coverage, lastBin + 1, binCount - 1, sideThreshold)) return null;

        return new Gap(startPt, endPt);
    }

    private static boolean hasCoverage(int[] coverage, int fromBin, int toBin, float threshold) {
        for (int bin = Math.max(0, fromBin); bin <= Math.min(toBin, coverage.length - 1); bin++) {
            if (coverage[bin] >= threshold) return true;
        }
        return false;
    }

    /**
     * Rejects a gutter that leaves one side nearly empty.
     *
     * <p>A page whose text merely happens to have a wide blank channel - a short centred
     * table, a figure above its caption - would otherwise be split into a real column and a
     * sliver, reordering content for no reason.
     */
    private static boolean plausible(ColumnLayout layout, List<TextRun> runs) {
        long left = 0;
        long right = 0;
        for (TextRun run : runs) {
            if (layout.spansColumns(run)) continue;
            if (layout.columnOf(run) == 0) left++;
            else right++;
        }
        long total = left + right;
        if (total == 0) return false;
        float minimum = total * MIN_COLUMN_SHARE;
        return left >= minimum && right >= minimum;
    }

    /** A candidate gutter in page points. */
    private record Gap(float startPt, float endPt) {
        float widthPt() {
            return endPt - startPt;
        }
    }
}
