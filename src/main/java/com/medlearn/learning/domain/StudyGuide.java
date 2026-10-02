package com.medlearn.learning.domain;

import java.util.List;

/**
 * A chapter turned into something to study from, in the reader's language.
 *
 * <p>The field set is the point. Asking a model for "a summary" yields prose that is pleasant
 * and hard to revise from; asking for objectives, mechanisms, pearls, red flags and self-test
 * questions yields the structure a resident actually revises against, and makes each part
 * separately checkable.
 *
 * <p>Deliberately free of serialization annotations. The model-facing schema - whose field
 * descriptions are really prompt text - lives beside the adapter that uses it, so a change of
 * model or SDK cannot reach into the domain.
 */
public record StudyGuide(
        String chapterTitle,

        List<String> learningObjectives,

        List<Concept> keyConcepts,

        List<String> mechanisms,

        List<String> clinicalPearls,

        List<String> redFlags,

        List<String> mnemonics,

        List<String> highYieldFacts,

        List<SelfTestItem> selfTest) {

    public StudyGuide {
        learningObjectives = safe(learningObjectives);
        keyConcepts = safe(keyConcepts);
        mechanisms = safe(mechanisms);
        clinicalPearls = safe(clinicalPearls);
        redFlags = safe(redFlags);
        mnemonics = safe(mnemonics);
        highYieldFacts = safe(highYieldFacts);
        selfTest = safe(selfTest);
        if (chapterTitle == null || chapterTitle.isBlank()) chapterTitle = "Chapter";
    }

    private static <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    public record Concept(
            String term,

            String definition,

            String whyItMatters) {}

    public record SelfTestItem(
            String question,

            String answer,

            String explanation) {}
}
