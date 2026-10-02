package com.medlearn.shared;

import java.util.stream.IntStream;

/**
 * Inclusive 1-based page range.
 *
 * <p>1-based because every other page number a reader sees - the PDF viewer, the
 * printed folio, a chapter's "starts on page 412" - is 1-based. PDFBox is 0-based
 * internally, and {@link #toZeroBasedStart()} is the single place that converts.
 */
public record PageRange(int startPage, int endPage) {

    public PageRange {
        if (startPage < 1) {
            throw new IllegalArgumentException("startPage is 1-based, got: " + startPage);
        }
        if (endPage < startPage) {
            throw new IllegalArgumentException(
                    "endPage %d precedes startPage %d".formatted(endPage, startPage));
        }
    }

    public static PageRange of(int startPage, int endPage) {
        return new PageRange(startPage, endPage);
    }

    public static PageRange singlePage(int page) {
        return new PageRange(page, page);
    }

    public int pageCount() {
        return endPage - startPage + 1;
    }

    public boolean contains(int page) {
        return page >= startPage && page <= endPage;
    }

    public int toZeroBasedStart() {
        return startPage - 1;
    }

    public IntStream pages() {
        return IntStream.rangeClosed(startPage, endPage);
    }

    /** Smallest range covering both. Used to collapse a chapter selection into one span. */
    public PageRange union(PageRange other) {
        return new PageRange(Math.min(startPage, other.startPage), Math.max(endPage, other.endPage));
    }

    @Override
    public String toString() {
        return startPage == endPage ? "p" + startPage : "p%d-%d".formatted(startPage, endPage);
    }
}
