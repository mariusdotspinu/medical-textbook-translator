package com.medlearn.ingestion.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Identifies running heads and page numbers so they are not translated.
 *
 * <p>On a 1000-page textbook the running head appears 1000 times. Translating it wastes
 * tokens on the same string a thousand times over, and re-rendering it risks disturbing the
 * page furniture a reader navigates by. Page numbers are worse: translating "412" is
 * meaningless, and a model asked to translate a bare number sometimes helpfully reformats it.
 *
 * <p><b>Why this needs two passes.</b> Whether a line is a running head cannot be decided
 * from its own page - "Chapter 12 Cardiology" at the top of a page could equally be a real
 * heading. It is only recognisable as furniture because it repeats across many pages at the
 * same position. So pass one observes every page and counts normalised candidates; pass two
 * reassigns roles once the counts are known.
 *
 * <p>Normalisation maps digits to {@code #}, so "page 41" and "page 412" collapse to the
 * same candidate. Without that, a folio would look unique on every page and never be caught.
 *
 * <p>{@link #observe} is safe to call from the parallel extraction workers.
 */
public final class HeaderFooterFilter {

    /** Fraction of page height treated as the header band. */
    private static final float HEADER_BAND = 0.12f;

    /** Fraction of page height treated as the footer band. */
    private static final float FOOTER_BAND = 0.88f;

    /** A candidate must repeat on at least this share of pages to count as furniture. */
    private static final float REPEAT_THRESHOLD = 0.30f;

    /** Below this many pages, repetition means nothing. */
    private static final int MIN_PAGES_FOR_DETECTION = 5;

    /** Furniture is short; a long paragraph at the top of a page is real content. */
    private static final int MAX_FURNITURE_CHARS = 120;

    private final Map<String, AtomicInteger> candidateCounts = new ConcurrentHashMap<>();
    private final AtomicInteger observedPages = new AtomicInteger();

    /** Pass one. Thread-safe. */
    public void observe(PageLayout page) {
        observedPages.incrementAndGet();
        for (TextBlock block : page.blocks()) {
            candidateKey(block, page.heightPt()).ifPresent(key ->
                    candidateCounts.computeIfAbsent(key, ignored -> new AtomicInteger())
                            .incrementAndGet());
        }
    }

    /** Candidates seen often enough to be page furniture. */
    public Set<String> repeatedKeys() {
        int pages = observedPages.get();
        if (pages < MIN_PAGES_FOR_DETECTION) return Set.of();

        int minimum = Math.max(2, (int) Math.ceil(pages * REPEAT_THRESHOLD));
        return candidateCounts.entrySet().stream()
                .filter(entry -> entry.getValue().get() >= minimum)
                .map(Map.Entry::getKey)
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Pass two: reassigns roles on one page.
     *
     * @return the page with furniture blocks re-roled, or the same instance if nothing changed
     */
    public PageLayout apply(PageLayout page, Set<String> repeated) {
        if (page.blocks().isEmpty()) return page;

        List<TextBlock> updated = new ArrayList<>(page.blocks().size());
        boolean changed = false;

        for (TextBlock block : page.blocks()) {
            TextRole role = roleFor(block, page.heightPt(), repeated);
            if (role != block.role()) {
                updated.add(block.withRole(role));
                changed = true;
            } else {
                updated.add(block);
            }
        }
        return changed ? page.withBlocks(updated) : page;
    }

    private TextRole roleFor(TextBlock block, float pageHeight, Set<String> repeated) {
        Band band = bandOf(block, pageHeight);
        if (band == Band.BODY) return block.role();

        // A bare number in a header or footer band is a folio regardless of repetition -
        // its normalised form is identical everywhere, but so is a one-off page reference.
        if (isBareNumber(block.text())) return TextRole.FOLIO;

        Optional<String> key = candidateKey(block, pageHeight);
        if (key.isEmpty() || !repeated.contains(key.get())) return block.role();

        return band == Band.HEADER ? TextRole.RUNNING_HEAD : TextRole.FOLIO;
    }

    /**
     * Key combining normalised text with the band, so the same string appearing as a running
     * head on some pages and as body text on others is not confused.
     *
     * @return empty when the block is not a furniture candidate at all
     */
    private Optional<String> candidateKey(TextBlock block, float pageHeight) {
        Band band = bandOf(block, pageHeight);
        if (band == Band.BODY) return Optional.empty();
        if (block.text().length() > MAX_FURNITURE_CHARS) return Optional.empty();

        return Optional.of(band.name() + '|' + normalize(block.text()));
    }

    private static Band bandOf(TextBlock block, float pageHeight) {
        if (pageHeight <= 0) return Band.BODY;
        float relativeTop = block.box().y() / pageHeight;
        float relativeBottom = block.box().bottom() / pageHeight;

        if (relativeBottom <= HEADER_BAND) return Band.HEADER;
        if (relativeTop >= FOOTER_BAND) return Band.FOOTER;
        return Band.BODY;
    }

    /** Digits collapse to {@code #} so that folios and "page N" heads match across pages. */
    static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT)
                .replaceAll("\\d+", "#")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static boolean isBareNumber(String text) {
        String trimmed = text.trim();
        if (trimmed.isEmpty() || trimmed.length() > 8) return false;
        // Roman numerals are used for front matter in medical textbooks.
        return trimmed.matches("\\d+") || trimmed.matches("(?i)[ivxlcdm]+");
    }

    private enum Band { HEADER, FOOTER, BODY }
}
