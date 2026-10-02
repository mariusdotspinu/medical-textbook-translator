package com.medlearn.learning.adapter.ai;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.medlearn.learning.domain.StudyGuide;

import java.util.List;

/**
 * The wire shape the model fills in, and the schema the SDK derives from it.
 *
 * <p>Separate from {@link StudyGuide} for a reason that is easy to miss: the
 * {@code @JsonPropertyDescription} text here is not documentation, it is <em>prompt</em> -
 * instructions aimed at one vendor's schema generator. Putting it on the domain record would
 * bake Jackson and a particular model's prompting into the heart of the application, which
 * is exactly what the architecture test forbids.
 *
 * <p>Jackson 2 annotations, not Jackson 3: the Anthropic SDK uses 2.x, while Spring Boot 4
 * serves web payloads with 3.x.
 */
record StudyGuideSchema(
        @JsonPropertyDescription("The chapter title, in the target language.")
        String chapterTitle,

        @JsonPropertyDescription(
                "3-6 learning objectives, each a concrete capability the reader should have "
                        + "after studying the chapter. Start each with a verb.")
        List<String> learningObjectives,

        @JsonPropertyDescription(
                "The chapter's key concepts. Include every term a reader must know to use "
                        + "the chapter clinically.")
        List<ConceptSchema> keyConcepts,

        @JsonPropertyDescription(
                "Pathophysiological mechanisms as ordered causal chains, written step by step "
                        + "so the reader can reconstruct the reasoning rather than memorise a "
                        + "conclusion.")
        List<String> mechanisms,

        @JsonPropertyDescription(
                "Clinical pearls: practical points that change management and are easy to "
                        + "miss from the text alone.")
        List<String> clinicalPearls,

        @JsonPropertyDescription(
                "Red flags: findings that demand urgent action, and what that action is.")
        List<String> redFlags,

        @JsonPropertyDescription(
                "Mnemonics or memory aids in the target language, only where one genuinely "
                        + "helps. An empty list is better than a forced acronym.")
        List<String> mnemonics,

        @JsonPropertyDescription(
                "High-yield facts: exact figures, doses, cut-offs and criteria from the "
                        + "chapter, reproduced precisely.")
        List<String> highYieldFacts,

        @JsonPropertyDescription(
                "Self-test questions with answers and short explanations, covering the "
                        + "chapter's reasoning rather than isolated recall.")
        List<SelfTestSchema> selfTest) {

    record ConceptSchema(
            @JsonPropertyDescription("The term in the target language, with the original "
                    + "English or Latin term in parentheses on first use.")
            String term,

            @JsonPropertyDescription("A precise one- or two-sentence definition.")
            String definition,

            @JsonPropertyDescription("Why this matters clinically.")
            String whyItMatters) {}

    record SelfTestSchema(
            @JsonPropertyDescription("The question, in the target language.")
            String question,

            @JsonPropertyDescription("The correct answer, stated directly.")
            String answer,

            @JsonPropertyDescription("A short explanation of why that is the answer.")
            String explanation) {}

    StudyGuide toDomain() {
        return new StudyGuide(
                chapterTitle,
                learningObjectives,
                keyConcepts == null ? List.of() : keyConcepts.stream()
                        .filter(java.util.Objects::nonNull)
                        .map(c -> new StudyGuide.Concept(c.term(), c.definition(), c.whyItMatters()))
                        .toList(),
                mechanisms,
                clinicalPearls,
                redFlags,
                mnemonics,
                highYieldFacts,
                selfTest == null ? List.of() : selfTest.stream()
                        .filter(java.util.Objects::nonNull)
                        .map(q -> new StudyGuide.SelfTestItem(
                                q.question(), q.answer(), q.explanation()))
                        .toList());
    }
}
