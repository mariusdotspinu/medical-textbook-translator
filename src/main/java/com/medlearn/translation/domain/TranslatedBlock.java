package com.medlearn.translation.domain;

/**
 * One translated unit, carrying the id of the source block it came from.
 *
 * <p>The id is what makes a dropped, merged or reordered block detectable. Without it a
 * model that silently returned 19 translations for 20 blocks would produce a plausible PDF
 * missing a paragraph, with nothing to catch it.
 */
public record TranslatedBlock(String blockId, String targetText) {

    public TranslatedBlock {
        if (blockId == null || blockId.isBlank()) {
            throw new IllegalArgumentException("blockId is required");
        }
        if (targetText == null) throw new IllegalArgumentException("targetText is required");
    }
}
