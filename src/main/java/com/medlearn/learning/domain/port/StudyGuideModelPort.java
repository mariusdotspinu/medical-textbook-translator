package com.medlearn.learning.domain.port;

import com.medlearn.learning.domain.StudyGuide;
import com.medlearn.shared.LanguageTag;

import java.util.Map;

/**
 * Produces a study guide from already-translated chapter text.
 *
 * <p>Fed the translation rather than the source on purpose: the guide then uses exactly the
 * terminology the reader just saw in the translated chapter, instead of inventing a second
 * rendering of every term.
 */
public interface StudyGuideModelPort {

    boolean billable();

    StudyGuide generate(Request request);

    /**
     * @param translatedText the chapter in the target language
     * @param glossary source term to agreed target term, so the guide uses the same wording
     *                 as the chapter. Deliberately a plain map rather than the translation
     *                 context's own type: keeping learning free of that dependency is what
     *                 stops translation and learning forming a cycle, since translation is
     *                 the side that drives this.
     */
    record Request(
            String chapterTitle,
            String translatedText,
            LanguageTag targetLanguage,
            Map<String, String> glossary) {

        public Request {
            if (translatedText == null || translatedText.isBlank()) {
                throw new IllegalArgumentException("nothing to summarise");
            }
            glossary = glossary == null ? Map.of() : Map.copyOf(glossary);
        }
    }
}
