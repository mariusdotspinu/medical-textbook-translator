package com.medlearn.ingestion.domain;

/**
 * Typeface of a text block as it appears in the source document.
 *
 * <p>Carried through extraction for two reasons: heading detection uses size outliers,
 * and the overlay renderer matches the original size so translated text sits at the same
 * visual weight as what it replaces.
 *
 * @param name     PostScript font name as embedded, possibly with a subset prefix
 * @param sizePt   rendered size in points
 * @param bold     inferred from the font name or its embedded weight
 * @param italic   inferred from the font name or its italic angle
 */
public record FontInfo(String name, float sizePt, boolean bold, boolean italic) {

    public FontInfo {
        if (name == null || name.isBlank()) name = "unknown";
        if (sizePt < 0) {
            throw new IllegalArgumentException("font size cannot be negative: " + sizePt);
        }
    }

    public static FontInfo of(String name, float sizePt) {
        String lower = name == null ? "" : name.toLowerCase(java.util.Locale.ROOT);
        boolean bold = lower.contains("bold") || lower.contains("black") || lower.contains("heavy");
        boolean italic = lower.contains("italic") || lower.contains("oblique");
        return new FontInfo(name, sizePt, bold, italic);
    }

    /**
     * Subset-embedded fonts carry a six-letter tag like {@code ABCDEF+Minion-Regular}.
     * Stripping it lets two blocks using the same face compare equal.
     */
    public String baseName() {
        int plus = name.indexOf('+');
        return plus == 6 ? name.substring(plus + 1) : name;
    }
}
