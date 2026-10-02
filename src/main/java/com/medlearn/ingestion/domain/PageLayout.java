package com.medlearn.ingestion.domain;

import java.util.Comparator;
import java.util.List;

/**
 * Everything extraction knows about one page: its geometry and its text blocks in
 * reading order.
 *
 * <p>This is the unit persisted to {@code page_layout}, zstd-compressed. It is also the
 * unit the overlay renderer consumes, which is why geometry is kept alongside the text -
 * re-deriving page size at render time would mean reopening the source document.
 *
 * @param pageNo   1-based page number as a reader would cite it
 * @param widthPt  page width in points
 * @param heightPt page height in points
 */
public record PageLayout(
        int pageNo,
        float widthPt,
        float heightPt,
        PageKind kind,
        List<TextBlock> blocks) {

    public PageLayout {
        if (pageNo < 1) throw new IllegalArgumentException("pageNo is 1-based, got: " + pageNo);
        if (widthPt <= 0 || heightPt <= 0) {
            throw new IllegalArgumentException(
                    "page must have positive dimensions: %fx%f".formatted(widthPt, heightPt));
        }
        if (kind == null) kind = PageKind.TEXT;
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
    }

    public static PageLayout empty(int pageNo, float widthPt, float heightPt) {
        return new PageLayout(pageNo, widthPt, heightPt, PageKind.EMPTY, List.of());
    }

    public int charCount() {
        return blocks.stream().mapToInt(TextBlock::charCount).sum();
    }

    public List<TextBlock> translatableBlocks() {
        return blocks.stream().filter(TextBlock::translatable).toList();
    }

    public int columnCount() {
        return blocks.stream().mapToInt(TextBlock::columnIndex).max().orElse(0) + 1;
    }

    /** Blocks sorted by column, then vertical position - the order a human reads them. */
    public List<TextBlock> inReadingOrder() {
        return blocks.stream()
                .sorted(Comparator.comparingInt(TextBlock::columnIndex)
                        .thenComparing(b -> b.box().y())
                        .thenComparing(b -> b.box().x()))
                .toList();
    }

    public PageLayout withBlocks(List<TextBlock> newBlocks) {
        return new PageLayout(pageNo, widthPt, heightPt, kind, newBlocks);
    }

    public PageLayout withKind(PageKind newKind) {
        return new PageLayout(pageNo, widthPt, heightPt, newKind, blocks);
    }
}
