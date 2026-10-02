package com.medlearn.ingestion.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Line grouping is pure geometry, so it is tested against synthetic coordinates rather than
 * a real PDF. That is the whole point of keeping it out of the PDFBox adapter.
 */
class LineGrouperTest {

    private static final float BODY_SIZE = 10f;
    private static final float SPACE_WIDTH = 3f;

    private static TextRun run(String text, float x, float y, float width) {
        return run(text, x, y, width, BODY_SIZE);
    }

    private static TextRun run(String text, float x, float y, float width, float size) {
        return new TextRun(text, x, y, width, size, "Minion-Regular", size, SPACE_WIDTH);
    }

    @Test
    @DisplayName("words at the same vertical position become one line")
    void mergesWordsOnTheSameBaseline() {
        List<TextBlock> blocks = LineGrouper.groupIntoLines(7, List.of(
                run("The", 50, 100, 20),
                run("left", 75, 100, 18),
                run("ventricle", 98, 100, 45)));

        assertThat(blocks).hasSize(1);
        assertThat(blocks.getFirst().text()).isEqualTo("The left ventricle");
        assertThat(blocks.getFirst().id()).isEqualTo("p7-b0");
    }

    @Test
    @DisplayName("words far apart vertically become separate lines, ordered top to bottom")
    void separatesDistinctLines() {
        List<TextBlock> blocks = LineGrouper.groupIntoLines(1, List.of(
                run("second", 50, 130, 30),
                run("first", 50, 100, 25)));

        assertThat(blocks).hasSize(2);
        assertThat(blocks.stream().map(TextBlock::text)).containsExactly("first", "second");
        assertThat(blocks.get(0).orderInPage()).isZero();
        assertThat(blocks.get(1).orderInPage()).isEqualTo(1);
    }

    @Test
    @DisplayName("a small vertical jitter still counts as the same line")
    void toleratesBaselineJitter() {
        // Superscripts and small caps shift the reported y slightly within a single line.
        List<TextBlock> blocks = LineGrouper.groupIntoLines(1, List.of(
                run("Na", 50, 100, 15),
                run("+", 66, 98.5f, 5, 7f),
                run("channel", 73, 100, 35)));

        assertThat(blocks).hasSize(1);
        assertThat(blocks.getFirst().text()).isEqualTo("Na + channel");
    }

    @Test
    @DisplayName("kerned-but-adjacent runs are joined without inserting a space")
    void doesNotInventSpacesBetweenTightlyKernedRuns() {
        // Ligature splits emit adjacent runs with a near-zero gap; a space here would
        // corrupt the term before it ever reaches the model.
        List<TextBlock> blocks = LineGrouper.groupIntoLines(1, List.of(
                run("fi", 50, 100, 8),
                run("brillation", 58.2f, 100, 40)));

        assertThat(blocks.getFirst().text()).isEqualTo("fibrillation");
    }

    @Test
    @DisplayName("the font covering the most characters wins")
    void picksDominantFontRatherThanTheFirstOne() {
        List<TextBlock> blocks = LineGrouper.groupIntoLines(1, List.of(
                new TextRun("A", 50, 100, 8, 10f, "Italic-Face", 10f, SPACE_WIDTH),
                new TextRun("considerably longer stretch", 60, 100, 120, 10f,
                        "Minion-Regular", 10f, SPACE_WIDTH)));

        assertThat(blocks.getFirst().font().name()).isEqualTo("Minion-Regular");
    }

    @Test
    @DisplayName("a markedly larger line is classified as a heading")
    void detectsHeadingsBySizeOutlier() {
        List<TextBlock> blocks = LineGrouper.groupIntoLines(1, List.of(
                run("Chapter 12 Cardiology", 50, 60, 200, 22f),
                run("body text here", 50, 100, 80),
                run("more body text", 50, 115, 80),
                run("and still more", 50, 130, 80)));

        assertThat(blocks.getFirst().role()).isEqualTo(TextRole.HEADING);
        assertThat(blocks.get(1).role()).isEqualTo(TextRole.BODY);
    }

    @Test
    @DisplayName("subset font prefixes are stripped when comparing faces")
    void stripsSubsetPrefixFromFontName() {
        FontInfo font = FontInfo.of("ABCDEF+Minion-Bold", 10f);
        assertThat(font.baseName()).isEqualTo("Minion-Bold");
        assertThat(font.bold()).isTrue();
    }

    @Test
    void emptyInputProducesNoBlocks() {
        assertThat(LineGrouper.groupIntoLines(1, List.of())).isEmpty();
        assertThat(LineGrouper.groupIntoLines(1, null)).isEmpty();
    }

}
