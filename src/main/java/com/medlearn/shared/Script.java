package com.medlearn.shared;

/**
 * Writing system of a target language, as far as PDF typesetting cares.
 *
 * <p>This drives two things that silently produce unreadable output if they are wrong:
 * which font must be embedded (a font without the glyph renders a blank box, with no
 * error anywhere), and how lines may be broken.
 */
public enum Script {

    /** English, Romanian, French, German, Spanish, Italian, ... */
    LATIN(LineBreaking.WORD),

    /** Russian, Bulgarian, Ukrainian, Serbian, Macedonian. */
    CYRILLIC(LineBreaking.WORD),

    GREEK(LineBreaking.WORD),

    /** Chinese, Japanese, Korean - no inter-word spaces, so lines break between characters. */
    CJK(LineBreaking.CHARACTER);

    public enum LineBreaking { WORD, CHARACTER }

    private final LineBreaking lineBreaking;

    Script(LineBreaking lineBreaking) {
        this.lineBreaking = lineBreaking;
    }

    public LineBreaking lineBreaking() {
        return lineBreaking;
    }

    /** CJK faces are ~20MB each, so they are loaded from disk rather than bundled in the jar. */
    public boolean requiresExternalFont() {
        return this == CJK;
    }
}
