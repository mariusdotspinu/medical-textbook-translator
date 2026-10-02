package com.medlearn.ingestion.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The tests that matter most in this codebase.
 *
 * <p>A two-column page whose columns get merged produces text that reads fluently and means
 * nothing, and it survives translation looking entirely plausible. These cases pin that down
 * on synthetic geometry modelled on a US Letter medical page: 612pt wide, two 240pt columns
 * at x=54 and x=318, with a 24pt gutter between them.
 */
class PageSegmenterTest {

    private static final float PAGE_WIDTH = 612f;
    private static final float LEFT_COLUMN_X = 54f;
    private static final float RIGHT_COLUMN_X = 318f;
    private static final float COLUMN_WIDTH = 240f;
    private static final float BODY_SIZE = 9.5f;
    private static final float LINE_HEIGHT = 11f;
    private static final float LEADING = 16f;

    @Test
    @DisplayName("two columns are never merged into one line")
    void keepsColumnsSeparate() {
        List<TextRun> runs = new ArrayList<>();
        // 20 lines per column, left and right sharing every baseline - the exact condition
        // that previously concatenated unrelated sentences.
        for (int i = 0; i < 20; i++) {
            float y = 90 + i * LEADING;
            addLine(runs, "left sentence number " + i, LEFT_COLUMN_X, y, COLUMN_WIDTH);
            addLine(runs, "right sentence number " + i, RIGHT_COLUMN_X, y, COLUMN_WIDTH);
        }

        List<TextBlock> blocks = PageSegmenter.segment(1, runs, PAGE_WIDTH);

        // No block may contain text from both columns.
        for (TextBlock block : blocks) {
            boolean hasLeft = block.text().contains("left sentence");
            boolean hasRight = block.text().contains("right sentence");
            assertThat(hasLeft && hasRight)
                    .as("block must not mix columns: %s", block.text())
                    .isFalse();
        }

        // And no block may span the full page measure.
        assertThat(blocks).allSatisfy(block ->
                assertThat(block.box().width()).isLessThan(PAGE_WIDTH * 0.6f));
    }

    @Test
    @DisplayName("the left column is read completely before the right column")
    void readsLeftColumnFirst() {
        List<TextRun> runs = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            float y = 90 + i * LEADING;
            addLine(runs, "LEFT" + i, LEFT_COLUMN_X, y, COLUMN_WIDTH);
            addLine(runs, "RIGHT" + i, RIGHT_COLUMN_X, y, COLUMN_WIDTH);
        }

        List<TextBlock> blocks = PageSegmenter.segment(1, runs, PAGE_WIDTH);
        String order = String.join(" ", blocks.stream().map(TextBlock::text).toList());

        assertThat(order.indexOf("LEFT0")).isLessThan(order.indexOf("RIGHT0"));
        assertThat(order.indexOf("LEFT19")).isLessThan(order.indexOf("RIGHT0"));
        assertThat(blocks.stream().mapToInt(TextBlock::columnIndex).max().orElseThrow()).isEqualTo(1);
    }

    @Test
    @DisplayName("a full-width heading is read before both columns below it")
    void placesFullWidthHeadingBeforeTheColumns() {
        List<TextRun> runs = new ArrayList<>();
        // Heading spans the gutter: x=54 to x=558.
        addLine(runs, "ACUTE CORONARY SYNDROMES", LEFT_COLUMN_X, 55, 504f, 16f);
        for (int i = 0; i < 20; i++) {
            float y = 90 + i * LEADING;
            addLine(runs, "LEFT" + i, LEFT_COLUMN_X, y, COLUMN_WIDTH);
            addLine(runs, "RIGHT" + i, RIGHT_COLUMN_X, y, COLUMN_WIDTH);
        }

        List<TextBlock> blocks = PageSegmenter.segment(1, runs, PAGE_WIDTH);

        assertThat(blocks.getFirst().text()).isEqualTo("ACUTE CORONARY SYNDROMES");
        assertThat(blocks.getFirst().role()).isEqualTo(TextRole.HEADING);
    }

    @Test
    @DisplayName("a mid-page full-width heading splits the page into bands")
    void ordersBandsAroundAMidPageSpanningElement() {
        List<TextRun> runs = new ArrayList<>();
        // Band 1: both columns, y 90-250.
        for (int i = 0; i < 10; i++) {
            float y = 90 + i * LEADING;
            addLine(runs, "A-LEFT" + i, LEFT_COLUMN_X, y, COLUMN_WIDTH);
            addLine(runs, "A-RIGHT" + i, RIGHT_COLUMN_X, y, COLUMN_WIDTH);
        }
        // Full-width divider at y=300.
        addLine(runs, "TABLE 1 DIFFERENTIAL DIAGNOSIS", LEFT_COLUMN_X, 300, 504f, 13f);
        // Band 2: both columns, y 330-490.
        for (int i = 0; i < 10; i++) {
            float y = 330 + i * LEADING;
            addLine(runs, "B-LEFT" + i, LEFT_COLUMN_X, y, COLUMN_WIDTH);
            addLine(runs, "B-RIGHT" + i, RIGHT_COLUMN_X, y, COLUMN_WIDTH);
        }

        List<String> texts = PageSegmenter.segment(1, runs, PAGE_WIDTH)
                .stream().map(TextBlock::text).toList();
        String order = String.join(" | ", texts);

        // Everything in band 1 precedes the divider; everything in band 2 follows it.
        int divider = order.indexOf("TABLE 1");
        assertThat(order.indexOf("A-LEFT0")).isLessThan(divider);
        assertThat(order.indexOf("A-RIGHT9")).isLessThan(divider);
        assertThat(order.indexOf("B-LEFT0")).isGreaterThan(divider);
        assertThat(order.indexOf("B-RIGHT9")).isGreaterThan(divider);
    }

    @Test
    @DisplayName("a genuinely single-column page is not split")
    void leavesSingleColumnPagesAlone() {
        List<TextRun> runs = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            addLine(runs, "full measure line number " + i, LEFT_COLUMN_X, 90 + i * LEADING, 504f);
        }

        List<TextBlock> blocks = PageSegmenter.segment(1, runs, PAGE_WIDTH);

        assertThat(blocks).allSatisfy(block -> assertThat(block.columnIndex()).isZero());
    }

    @Test
    @DisplayName("consecutive body lines merge into a paragraph, with hyphenation undone")
    void mergesLinesIntoParagraphsAndDehyphenates() {
        List<TextRun> runs = new ArrayList<>();
        // Three tight, full-measure lines: one paragraph. "myocar-" / "dial" must rejoin.
        addLine(runs, "The patient presented with acute myocar-", LEFT_COLUMN_X, 90, 500f);
        addLine(runs, "dial infarction confirmed by troponin and", LEFT_COLUMN_X, 106, 500f);
        addLine(runs, "serial electrocardiograms over four hours.", LEFT_COLUMN_X, 122, 500f);
        for (int i = 0; i < 25; i++) {
            addLine(runs, "padding line to make the page realistic " + i,
                    LEFT_COLUMN_X, 200 + i * LEADING, 500f);
        }

        List<TextBlock> blocks = PageSegmenter.segment(1, runs, PAGE_WIDTH);

        assertThat(blocks.getFirst().text())
                .contains("acute myocardial infarction")
                .doesNotContain("myocar- dial")
                .doesNotContain("myocar-dial");
    }

    @Test
    @DisplayName("a hyphen before an uppercase continuation is kept")
    void keepsHyphenInCompoundsAcrossALineBreak() {
        List<TextRun> runs = new ArrayList<>();
        addLine(runs, "inhibition of the enzyme known as COX-", LEFT_COLUMN_X, 90, 500f);
        addLine(runs, "2 reduces prostaglandin synthesis here.", LEFT_COLUMN_X, 106, 500f);
        for (int i = 0; i < 25; i++) {
            addLine(runs, "padding line number " + i, LEFT_COLUMN_X, 200 + i * LEADING, 500f);
        }

        List<TextBlock> blocks = PageSegmenter.segment(1, runs, PAGE_WIDTH);

        assertThat(blocks.getFirst().text()).contains("COX- 2").doesNotContain("COX2");
    }

    @Test
    @DisplayName("block ids are unique across a multi-column page")
    void mintsUniqueIdsAcrossColumns() {
        // Each column is grouped independently and numbers from zero, so without renumbering
        // a page would carry several blocks called p1-b0 and a translation could not be
        // matched back to its source.
        List<TextRun> runs = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            float y = 90 + i * LEADING;
            addLine(runs, "left " + i, LEFT_COLUMN_X, y, COLUMN_WIDTH);
            addLine(runs, "right " + i, RIGHT_COLUMN_X, y, COLUMN_WIDTH);
        }

        List<TextBlock> blocks = PageSegmenter.segment(1, runs, PAGE_WIDTH);

        assertThat(blocks.stream().map(TextBlock::id).distinct().count())
                .isEqualTo(blocks.size());
        assertThat(blocks.stream().map(TextBlock::orderInPage).distinct().count())
                .isEqualTo(blocks.size());
    }

    // --- fixture helpers ---------------------------------------------------

    private static void addLine(List<TextRun> sink, String text, float x, float y, float width) {
        addLine(sink, text, x, y, width, BODY_SIZE);
    }

    /**
     * Splits a line into per-word runs, which is what PDFBox actually reports.
     *
     * <p>Character and space widths are derived so the words plus their gaps fill exactly
     * {@code width}. Getting this right matters: oversized inter-word gaps read as
     * whitespace channels and the column detector would find a gutter that is not there.
     */
    private static void addLine(
            List<TextRun> sink, String text, float x, float y, float width, float size) {
        String[] words = text.split(" ");
        int glyphCount = text.replace(" ", "").length();
        float spaceWidth = size * 0.28f;
        float charWidth = Math.max(0.5f,
                (width - spaceWidth * (words.length - 1)) / Math.max(1, glyphCount));
        float height = size * 1.15f;

        float cursor = x;
        for (String word : words) {
            float wordWidth = charWidth * word.length();
            sink.add(new TextRun(word, cursor, y, wordWidth, height,
                    "Minion-Regular", size, spaceWidth));
            cursor += wordWidth + spaceWidth;
        }
    }
}
