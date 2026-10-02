package com.medlearn.translation.domain;

/**
 * Something that went wrong, or might have, with one translated block.
 *
 * <p>Sealed so that every finding must be handled explicitly when reporting or acting on
 * results; a new failure mode becomes a compile error rather than a silently ignored case.
 *
 * <p>Findings are not exceptions. A chapter with a few flagged blocks is still delivered -
 * with the problems named - because a doctor would rather have 300 good pages and a list of
 * five suspect paragraphs than no PDF at all.
 */
public sealed interface QualityFinding {

    String blockId();

    /** Whether this finding warrants spending another request to re-translate the block. */
    boolean worthRetrying();

    String describe();

    /**
     * Numbers or units differ between source and translation.
     *
     * <p>The most serious finding here: an altered dose in a medical textbook is a clinical
     * hazard, not a typo.
     */
    record NumericMismatch(String blockId, String expected, String actual) implements QualityFinding {
        @Override public boolean worthRetrying() { return true; }
        @Override public String describe() {
            return "numbers or units changed: source had [%s], translation has [%s]"
                    .formatted(expected, actual);
        }
    }

    /** Model returned nothing for a block that had text. */
    record EmptyTranslation(String blockId) implements QualityFinding {
        @Override public boolean worthRetrying() { return true; }
        @Override public String describe() { return "translation came back empty"; }
    }

    /** Translation is implausibly shorter or longer than its source. */
    record ImplausibleLength(String blockId, double ratio) implements QualityFinding {
        @Override public boolean worthRetrying() { return true; }
        @Override public String describe() {
            return "length ratio %.2f suggests truncation or padding".formatted(ratio);
        }
    }

    /** A do-not-translate placeholder was not put back. */
    record UnrestoredPlaceholder(String blockId, String placeholder) implements QualityFinding {
        @Override public boolean worthRetrying() { return true; }
        @Override public String describe() {
            return "placeholder %s was lost in translation".formatted(placeholder);
        }
    }

    /** The model never returned this block at all. */
    record MissingBlock(String blockId) implements QualityFinding {
        @Override public boolean worthRetrying() { return true; }
        @Override public String describe() { return "no translation returned for this block"; }
    }

    /**
     * Safety classifiers declined the content. Medical material - toxicology, controlled
     * substances, pathogens - can trip these.
     */
    record Refused(String blockId, String category) implements QualityFinding {
        @Override public boolean worthRetrying() { return false; }
        @Override public String describe() {
            return "the model declined this block (%s); it is left in the source language"
                    .formatted(category == null ? "no category given" : category);
        }
    }

    /** Translated text would not fit its box even after shrinking; the page was reflowed. */
    record Overflow(String blockId, int pageNo) implements QualityFinding {
        @Override public boolean worthRetrying() { return false; }
        @Override public String describe() {
            return "text did not fit its original box; page %d was reflowed instead".formatted(pageNo);
        }
    }
}
