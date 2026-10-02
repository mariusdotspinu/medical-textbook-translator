package com.medlearn.ingestion.domain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a printed table of contents from the lines of the book's opening pages.
 *
 * <p>Used only when the PDF has no bookmarks. A printed TOC is the next most reliable
 * structure a textbook offers, but it is text, so everything here is a heuristic that
 * rejects rather than guesses:
 *
 * <ol>
 *   <li><b>Find the pages.</b> A TOC page is one where most text lines end in a page number,
 *       with or without dot leaders. A "Contents" heading lowers the bar for the first page.
 *       Consecutive such pages form the TOC; a "List of figures" or similar ends it.
 *   <li><b>Parse entries.</b> "Title ..... 123". A line without a number directly before an
 *       entry is the first half of a title that wrapped. Entries numbered in roman numerals
 *       are front matter (preface, foreword) and are skipped.
 *   <li><b>Assign levels</b> from indentation, measured within each column so a two-column
 *       TOC does not turn its right column into a deeper level.
 *   <li><b>Check order.</b> Page numbers in a TOC only go forward. An entry that goes back is
 *       a misread line and is dropped; if many do, this was not a TOC.
 * </ol>
 *
 * <p>The printed numbers are returned as printed. Mapping them to PDF pages is
 * {@link TocPageMapper}'s job, since front matter usually shifts them.
 */
public final class TocParser {

    /** Pages read from the start of the book when looking for a TOC. */
    public static final int SCAN_PAGES = 50;

    private static final int MIN_ENTRIES_PER_PAGE = 4;
    private static final double MIN_ENTRY_RATIO = 0.5;
    private static final int MIN_ENTRIES = 3;
    private static final double MAX_BACKWARD_RATIO = 0.1;
    private static final float INDENT_STEP_PT = 8f;
    private static final int MAX_LEVELS = 5;

    private static final String PAGE_REF = "(\\d{1,4}|[ivxlcdm]{1,7})";

    /** "Title . . . . 12", "Title.......12", "Title ··· 12", "Title …… 12" */
    private static final Pattern WITH_LEADERS = Pattern.compile(
            "^(.*?\\p{L}.*?)\\s*(?:[.·•…_]\\s*){2,}" + PAGE_REF + "\\s*$", Pattern.CASE_INSENSITIVE);

    /** "Title 12": the number after whitespace, at the end of the line */
    private static final Pattern WITHOUT_LEADERS = Pattern.compile(
            "^(.*?\\p{L}.*?)\\s+" + PAGE_REF + "\\s*$", Pattern.CASE_INSENSITIVE);

    private static final Pattern CONTENTS_HEADING = Pattern.compile(
            "^(table of )?contents$|^brief contents$|^detailed contents$", Pattern.CASE_INSENSITIVE);

    private static final Pattern END_HEADING = Pattern.compile(
            "^list of (figures|tables|illustrations|abbreviations|boxes|contributors)$",
            Pattern.CASE_INSENSITIVE);

    private TocParser() {}

    /** One TOC entry, with the page number exactly as printed. */
    public record TocEntry(String title, int level, int printedPage) {}

    /**
     * @param firstPage PDF page of the TOC's first page
     * @param lastPage  PDF page of its last page; content starts after it
     */
    public record ParsedToc(List<TocEntry> entries, int firstPage, int lastPage) {}

    /**
     * @param linesByPage the lines of the book's opening pages, by PDF page number, each in
     *                    reading order
     */
    public static Optional<ParsedToc> parse(SortedMap<Integer, List<TextBlock>> linesByPage) {
        Integer first = null;
        Integer last = null;
        for (Map.Entry<Integer, List<TextBlock>> page : linesByPage.entrySet()) {
            boolean tocPage = isTocPage(page.getValue(), first == null);
            if (first == null) {
                if (tocPage) first = last = page.getKey();
            } else if (tocPage && page.getKey() == last + 1 && !startsWithEndHeading(page.getValue())) {
                last = page.getKey();
            } else {
                break;
            }
        }
        if (first == null) return Optional.empty();

        List<Line> tocLines = new ArrayList<>();
        for (int pageNo = first; pageNo <= last; pageNo++) {
            for (TextBlock line : linesByPage.get(pageNo)) {
                if (END_HEADING.matcher(clean(line.text())).matches()) break;
                tocLines.add(new Line(pageNo, line));
            }
        }

        List<TocEntry> entries = inForwardOrder(parseEntries(tocLines));
        if (entries.size() < MIN_ENTRIES) return Optional.empty();
        return Optional.of(new ParsedToc(entries, first, last));
    }

    private static boolean isTocPage(List<TextBlock> lines, boolean couldBeFirst) {
        int textLines = 0;
        int entryLines = 0;
        boolean contentsHeading = false;
        for (TextBlock line : lines) {
            String text = clean(line.text());
            if (!text.matches(".*\\p{L}.*")) continue;
            textLines++;
            if (CONTENTS_HEADING.matcher(text).matches()) contentsHeading = true;
            if (entryOf(text).isPresent()) entryLines++;
        }
        if (contentsHeading && couldBeFirst && entryLines >= 2) return true;
        return entryLines >= MIN_ENTRIES_PER_PAGE && entryLines >= textLines * MIN_ENTRY_RATIO;
    }

    private static boolean startsWithEndHeading(List<TextBlock> lines) {
        return lines.stream().map(line -> clean(line.text())).filter(text -> !text.isEmpty())
                .findFirst().map(text -> END_HEADING.matcher(text).matches()).orElse(false);
    }

    /** A line with the page it is on, which together with its column locates its margin. */
    private record Line(int page, TextBlock block) {
        String column() { return page + "/" + block.columnIndex(); }
        float x() { return block.box().x(); }
    }

    private static List<TocEntry> parseEntries(List<Line> lines) {
        Map<String, Float> columnLeft = new HashMap<>();
        for (Line line : lines) columnLeft.merge(line.column(), line.x(), Math::min);

        record Raw(String title, float indent, String page) {}
        List<Raw> raws = new ArrayList<>();
        Line pending = null;   // first line of a wrapped title

        for (Line line : lines) {
            String text = clean(line.block().text());
            if (text.isEmpty() || CONTENTS_HEADING.matcher(text).matches()) {
                pending = null;
                continue;
            }
            Optional<String[]> entry = entryOf(text);
            if (entry.isEmpty()) {
                // Only a lettered line can start a wrapped title; a lone number or rule cannot.
                pending = text.matches(".*\\p{L}.*") ? line : null;
                continue;
            }
            Line head = pending != null && line.x() >= pending.x() - INDENT_STEP_PT
                    && pending.column().equals(line.column()) ? pending : null;
            String title = head == null
                    ? entry.get()[0]
                    : clean(head.block().text()) + " " + entry.get()[0];
            Line indentFrom = head == null ? line : head;
            raws.add(new Raw(title, indentFrom.x() - columnLeft.get(indentFrom.column()),
                    entry.get()[1]));
            pending = null;
        }

        List<Float> steps = indentSteps(raws.stream().map(Raw::indent).toList());
        List<TocEntry> entries = new ArrayList<>();
        for (Raw raw : raws) {
            if (!raw.page().matches("\\d+")) continue;   // roman numerals: front matter
            entries.add(new TocEntry(raw.title(), levelOf(raw.indent(), steps),
                    Integer.parseInt(raw.page())));
        }
        return entries;
    }

    /** @return {title, page} if the line ends in a page reference */
    static Optional<String[]> entryOf(String text) {
        for (Pattern pattern : List.of(WITH_LEADERS, WITHOUT_LEADERS)) {
            Matcher matcher = pattern.matcher(text);
            if (matcher.matches()) {
                String title = matcher.group(1).replaceAll("[\\s.·•…_]+$", "").strip();
                if (title.length() >= 2 && title.length() <= 200) {
                    return Optional.of(new String[]{title, matcher.group(2).toLowerCase(Locale.ROOT)});
                }
            }
        }
        return Optional.empty();
    }

    /** Distinct indents, clustered within {@link #INDENT_STEP_PT}, smallest first. */
    private static List<Float> indentSteps(List<Float> indents) {
        List<Float> steps = new ArrayList<>();
        indents.stream().sorted().forEach(indent -> {
            if (steps.isEmpty() || indent - steps.getLast() > INDENT_STEP_PT) steps.add(indent);
        });
        return steps;
    }

    private static int levelOf(float indent, List<Float> steps) {
        int level = 0;
        for (int i = 0; i < steps.size(); i++) {
            if (indent >= steps.get(i) - INDENT_STEP_PT / 2) level = i;
        }
        return Math.min(level, MAX_LEVELS - 1);
    }

    /** Drops entries whose page goes backwards; rejects the lot if too many do. */
    private static List<TocEntry> inForwardOrder(List<TocEntry> entries) {
        List<TocEntry> kept = new ArrayList<>();
        int backward = 0;
        int highest = 0;
        for (TocEntry entry : entries) {
            if (entry.printedPage() < highest) {
                backward++;
                continue;
            }
            highest = entry.printedPage();
            kept.add(entry);
        }
        if (backward > Math.max(1, entries.size() * MAX_BACKWARD_RATIO)) return List.of();
        return kept;
    }

    private static String clean(String text) {
        return text.replace(' ', ' ').replaceAll("\\s+", " ").strip();
    }
}
