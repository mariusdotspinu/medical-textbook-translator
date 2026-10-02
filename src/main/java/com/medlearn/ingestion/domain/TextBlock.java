package com.medlearn.ingestion.domain;

/**
 * One translatable unit of text with its position on the page.
 *
 * <p>Phase 1 emits one block per visual line; paragraph grouping and column assignment
 * refine these in place without changing the stored shape.
 *
 * @param id          stable within a page: {@code "p{page}-b{index}"}. This is the key the
 *                    model echoes back, which is what makes a dropped or merged block
 *                    detectable rather than silent.
 * @param text        source text, already whitespace-normalised
 * @param box         bounding box, top-left origin (see {@link BlockBox})
 * @param font        dominant font of the block
 * @param columnIndex 0-based column, assigned by column detection; 0 for single-column pages
 * @param orderInPage reading-order index within the page
 * @param role        whether this block should be translated
 */
public record TextBlock(
        String id,
        String text,
        BlockBox box,
        FontInfo font,
        int columnIndex,
        int orderInPage,
        TextRole role) {

    public TextBlock {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("block id is required");
        if (text == null) throw new IllegalArgumentException("block text is required (may be empty)");
        if (box == null) throw new IllegalArgumentException("block box is required");
        if (font == null) throw new IllegalArgumentException("block font is required");
        if (columnIndex < 0) throw new IllegalArgumentException("columnIndex cannot be negative");
        if (role == null) role = TextRole.BODY;
    }

    public static String idFor(int pageNo, int index) {
        return "p%d-b%d".formatted(pageNo, index);
    }

    public boolean translatable() {
        return role.translatable() && !text.isBlank();
    }

    public int charCount() {
        return text.length();
    }

    public TextBlock withColumn(int column) {
        return new TextBlock(id, text, box, font, column, orderInPage, role);
    }

    public TextBlock withOrder(int order) {
        return new TextBlock(id, text, box, font, columnIndex, order, role);
    }

    public TextBlock withRole(TextRole newRole) {
        return new TextBlock(id, text, box, font, columnIndex, orderInPage, newRole);
    }

    public TextBlock withText(String newText) {
        return new TextBlock(id, newText, box, font, columnIndex, orderInPage, role);
    }
}
