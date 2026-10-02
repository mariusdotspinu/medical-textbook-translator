package com.medlearn.translation.domain;

import com.medlearn.shared.LanguageTag;

/**
 * One agreed translation of a term, for one book.
 *
 * <p>The mechanism behind terminology consistency: a term is translated once, stored, and
 * then injected into every later request for that book, so the same word cannot come back
 * differently on page 940 than it did on page 40. It is also what makes responses cheaper -
 * the glossary sits in the cached prompt prefix.
 *
 * <p>Once a term is in the glossary, extraction never changes it: consistency is the point,
 * so only a human edit can. An edit pins the term.
 *
 * @param occurrences how often the term appears in the pages scanned so far, for ordering
 * @param pinned      set when a human added or corrected the term
 */
public record GlossaryTerm(
        GlossaryTermId id,
        String sourceTerm,
        String targetTerm,
        LanguageTag targetLanguage,
        TermKind kind,
        int occurrences,
        boolean pinned) {

    public GlossaryTerm {
        if (id == null) id = GlossaryTermId.newId();
        if (sourceTerm == null || sourceTerm.isBlank()) {
            throw new IllegalArgumentException("sourceTerm is required");
        }
        if (targetTerm == null || targetTerm.isBlank()) {
            throw new IllegalArgumentException("targetTerm is required");
        }
        sourceTerm = sourceTerm.strip();
        targetTerm = targetTerm.strip();
        if (kind == null) kind = TermKind.OTHER;
        if (occurrences < 0) occurrences = 0;
    }

    /** A human's entry or correction: pinned, so nothing automatic will change it. */
    public GlossaryTerm pinnedAs(String newTargetTerm, TermKind newKind) {
        return new GlossaryTerm(id, sourceTerm, newTargetTerm, targetLanguage,
                newKind == null ? kind : newKind, occurrences, true);
    }

    public enum TermKind {
        /** Terminologia Anatomica names, which keep their Latin form. */
        ANATOMY,
        /** International non-proprietary names, never localised. */
        DRUG,
        ABBREVIATION,
        CONDITION,
        OTHER
    }
}
