package com.medlearn.ingestion.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Merges consecutive lines of one column into paragraphs.
 *
 * <p>Worth doing before translation for two reasons. A model translating a whole paragraph
 * chooses agreement, tense and word order from the full sentence rather than guessing from a
 * fragment; and paragraphs mean far fewer, larger units, which cuts request count and lets
 * the prompt cache do more work.
 *
 * <p>Operates on a single column's lines - call it after columns are assigned, never before.
 */
public final class ParagraphMerger {

    /** Vertical gap above which two lines belong to different paragraphs. */
    private static final float MAX_GAP_RATIO = 0.8f;

    /** Font sizes within this ratio count as the same style. */
    private static final float SIZE_TOLERANCE = 0.15f;

    /** A line starting this far right of the column edge is an indented paragraph opening. */
    private static final float INDENT_TOLERANCE_PT = 4f;

    /** A line this much shorter than the column measure ends its paragraph. */
    private static final float SHORT_LINE_RATIO = 0.75f;

    private ParagraphMerger() {}

    /**
     * @param lines one column's lines, any order
     * @return paragraph blocks in top-to-bottom order; ids and ordering are assigned later
     *         by {@link PageSegmenter}
     */
    public static List<TextBlock> merge(List<TextBlock> lines) {
        if (lines == null || lines.isEmpty()) return List.of();
        if (lines.size() == 1) return List.copyOf(lines);

        List<TextBlock> ordered = lines.stream()
                .sorted(Comparator.comparing((TextBlock block) -> block.box().y())
                        .thenComparing(block -> block.box().x()))
                .toList();

        float columnLeft = (float) ordered.stream()
                .mapToDouble(block -> block.box().x()).min().orElse(0);
        float measure = columnMeasure(ordered);

        List<TextBlock> paragraphs = new ArrayList<>();
        List<TextBlock> current = new ArrayList<>();
        current.add(ordered.getFirst());

        for (int i = 1; i < ordered.size(); i++) {
            TextBlock previous = ordered.get(i - 1);
            TextBlock candidate = ordered.get(i);

            if (continuesParagraph(previous, candidate, columnLeft, measure)) {
                current.add(candidate);
            } else {
                paragraphs.add(join(current));
                current = new ArrayList<>();
                current.add(candidate);
            }
        }
        paragraphs.add(join(current));
        return List.copyOf(paragraphs);
    }

    private static boolean continuesParagraph(
            TextBlock previous, TextBlock candidate, float columnLeft, float measure) {

        // Headings stand alone - merging one into the body text that follows would hide the
        // structure the reader navigates by.
        if (previous.role() == TextRole.HEADING || candidate.role() == TextRole.HEADING) return false;

        float gap = candidate.box().y() - previous.box().bottom();
        float reference = Math.max(previous.box().height(), candidate.box().height());
        if (gap > reference * MAX_GAP_RATIO) return false;

        // Negative gap means overlapping boxes: not a following line at all.
        if (gap < -reference) return false;

        float previousSize = previous.font().sizePt();
        float candidateSize = candidate.font().sizePt();
        if (previousSize > 0 && Math.abs(previousSize - candidateSize) / previousSize > SIZE_TOLERANCE) {
            return false;
        }

        // An indented line opens a new paragraph - the standard book convention.
        if (candidate.box().x() > columnLeft + INDENT_TOLERANCE_PT) return false;

        // A short predecessor is a paragraph's last line.
        return !(measure > 0 && previous.box().width() < measure * SHORT_LINE_RATIO);
    }

    /** 90th-percentile line width: robust to one over-long line and to short final lines. */
    private static float columnMeasure(List<TextBlock> lines) {
        List<Float> widths = lines.stream()
                .map(block -> block.box().width())
                .sorted()
                .toList();
        int index = Math.min(widths.size() - 1, (int) Math.floor(widths.size() * 0.9));
        return widths.get(index);
    }

    private static TextBlock join(List<TextBlock> lines) {
        if (lines.size() == 1) return lines.getFirst();

        StringBuilder text = new StringBuilder(lines.getFirst().text());
        BlockBox box = lines.getFirst().box();

        for (int i = 1; i < lines.size(); i++) {
            appendLine(text, lines.get(i).text());
            box = box.union(lines.get(i).box());
        }

        TextBlock first = lines.getFirst();
        return new TextBlock(
                first.id(), text.toString(), box, first.font(),
                first.columnIndex(), first.orderInPage(), first.role());
    }

    /**
     * Appends a continuation line, undoing end-of-line hyphenation.
     *
     * <p>A trailing hyphen before a lowercase continuation is soft hyphenation, so
     * "myocar-" + "dial" has to become "myocardial" - leaving it split hands the model a
     * fragment it cannot translate. The hyphen is kept when the next line starts with an
     * uppercase letter or a digit, which is where real compounds land ("beta-" + "Blocker",
     * "COX-" + "2").
     *
     * <p>This is a heuristic and will occasionally fuse a genuine compound whose hyphen
     * happens to fall at a line break. That is the better error: a fused word is still
     * translatable, whereas a split one is not.
     */
    private static void appendLine(StringBuilder text, String nextLine) {
        if (nextLine.isEmpty()) return;

        int length = text.length();
        boolean hyphenated = length >= 2
                && text.charAt(length - 1) == '-'
                && Character.isLetter(text.charAt(length - 2))
                && Character.isLowerCase(nextLine.charAt(0));

        if (hyphenated) {
            text.setLength(length - 1);
            text.append(nextLine);
        } else {
            if (length > 0 && text.charAt(length - 1) != ' ') text.append(' ');
            text.append(nextLine);
        }
    }
}
