package com.medlearn.ingestion.domain;

import com.medlearn.ingestion.domain.TocParser.TocEntry;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.stream.IntStream;

/**
 * Works out which PDF page a printed TOC page number refers to.
 *
 * <p>They rarely coincide: the cover, title page, preface and the TOC itself come first, and
 * are often numbered in roman numerals, so printed page 1 may be PDF page 23. The offset is
 * found from evidence, in order of strength:
 *
 * <ol>
 *   <li><b>Printed page numbers.</b> Ingestion already recognises folios. When most pages
 *       agree on {@code pdfPage - printedNumber}, that is the offset.
 *   <li><b>Chapter titles.</b> Otherwise, each candidate offset is scored by how many TOC
 *       titles actually appear in the body text of the page it maps them to. Running heads
 *       are excluded from that text, since they repeat a chapter's title on every one of its
 *       pages and would make any offset within the chapter look right.
 * </ol>
 *
 * <p>No offset is better than a wrong one: a TOC that cannot be placed is not used, and the
 * book keeps its single "Full text" chapter.
 */
public final class TocPageMapper {

    private static final int MIN_AGREEING_FOLIOS = 3;
    private static final double MIN_FOLIO_AGREEMENT = 0.6;
    private static final double MIN_IN_RANGE = 0.9;
    private static final double MIN_TITLE_SCORE = 0.5;
    private static final int TITLE_PROBES = 5;
    private static final int TITLE_SEARCH_PAGES = 200;
    private static final int SCORE_SAMPLE = 30;
    private static final int MIN_TITLE_LETTERS = 4;

    private TocPageMapper() {}

    /** @param basis how the offset was established, for logs */
    public record Mapping(int offset, String basis) {}

    /**
     * @param printedByPdfPage printed page number per PDF page, from recognised folios
     * @param bodyText         a page's text without running heads and folios
     * @param firstContentPage the first PDF page after the TOC
     */
    public static Optional<Mapping> map(
            List<TocEntry> entries, Map<Integer, Integer> printedByPdfPage,
            IntFunction<String> bodyText, int firstContentPage, int pageCount) {

        Optional<Integer> fromFolios = folioOffset(printedByPdfPage, firstContentPage);
        if (fromFolios.isPresent() && inRange(entries, fromFolios.get(), firstContentPage, pageCount)) {
            return Optional.of(new Mapping(fromFolios.get(), "printed page numbers"));
        }

        Set<Integer> candidates = new LinkedHashSet<>();
        fromFolios.ifPresent(candidates::add);
        candidates.addAll(titleCandidates(entries, bodyText, firstContentPage, pageCount));

        List<TocEntry> sample = sample(entries);
        long checkable = sample.stream()
                .filter(entry -> letters(entry.title()).length() >= MIN_TITLE_LETTERS)
                .count();
        if (checkable == 0) return Optional.empty();

        Mapping best = null;
        long bestScore = 0;
        for (int offset : candidates) {
            if (!inRange(entries, offset, firstContentPage, pageCount)) continue;
            long score = sample.stream()
                    .filter(entry -> titleOnPage(entry, entry.printedPage() + offset, bodyText, pageCount))
                    .count();
            boolean better = score > bestScore
                    || (score == bestScore && best != null && Math.abs(offset) < Math.abs(best.offset()));
            if (better) {
                best = new Mapping(offset, "chapter titles found on their pages");
                bestScore = score;
            }
        }
        boolean convincing = best != null && bestScore >= Math.max(1, Math.ceil(checkable * MIN_TITLE_SCORE));
        return convincing ? Optional.of(best) : Optional.empty();
    }

    private static Optional<Integer> folioOffset(Map<Integer, Integer> printedByPdfPage, int firstContentPage) {
        Map<Integer, Integer> votes = new HashMap<>();
        int counted = 0;
        for (Map.Entry<Integer, Integer> folio : printedByPdfPage.entrySet()) {
            if (folio.getKey() < firstContentPage) continue;
            votes.merge(folio.getKey() - folio.getValue(), 1, Integer::sum);
            counted++;
        }
        int total = counted;
        return votes.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .filter(top -> top.getValue() >= MIN_AGREEING_FOLIOS
                        && top.getValue() >= total * MIN_FOLIO_AGREEMENT)
                .map(Map.Entry::getKey);
    }

    /** Offsets implied by where the first few titles first appear after the TOC. */
    private static Set<Integer> titleCandidates(
            List<TocEntry> entries, IntFunction<String> bodyText, int firstContentPage, int pageCount) {
        Set<Integer> offsets = new LinkedHashSet<>();
        int lastPage = Math.min(pageCount, firstContentPage + TITLE_SEARCH_PAGES);
        entries.stream()
                .filter(entry -> letters(entry.title()).length() >= MIN_TITLE_LETTERS)
                .limit(TITLE_PROBES)
                .forEach(entry -> IntStream.rangeClosed(firstContentPage, lastPage)
                        .filter(page -> titleOnPage(entry, page, bodyText, pageCount))
                        .findFirst()
                        .ifPresent(page -> offsets.add(page - entry.printedPage())));
        return offsets;
    }

    private static boolean inRange(List<TocEntry> entries, int offset, int firstContentPage, int pageCount) {
        long inside = entries.stream()
                .map(entry -> entry.printedPage() + offset)
                .filter(page -> page >= firstContentPage && page <= pageCount)
                .count();
        return inside >= entries.size() * MIN_IN_RANGE;
    }

    private static boolean titleOnPage(TocEntry entry, int page, IntFunction<String> bodyText, int pageCount) {
        if (page < 1 || page > pageCount) return false;
        String title = letters(entry.title());
        if (title.length() < MIN_TITLE_LETTERS) return false;
        String text = bodyText.apply(page);
        return text != null && letters(text).contains(title);
    }

    /** Evenly spaced entries, so a long TOC is scored across the whole book. */
    private static List<TocEntry> sample(List<TocEntry> entries) {
        if (entries.size() <= SCORE_SAMPLE) return entries;
        double step = (double) entries.size() / SCORE_SAMPLE;
        return IntStream.range(0, SCORE_SAMPLE).mapToObj(i -> entries.get((int) (i * step))).toList();
    }

    /**
     * Lower-case letters and single spaces only, without a leading "Chapter 3" or "2.1":
     * the TOC says "3 Heart failure" where the page says "CHAPTER 3: HEART FAILURE".
     */
    static String letters(String text) {
        String lower = text.toLowerCase(Locale.ROOT)
                .replaceFirst("^\\s*(chapter|part|section|unit)\\b", "");
        return lower.replaceAll("[^\\p{L}]+", " ").strip();
    }
}
