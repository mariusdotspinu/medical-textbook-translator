package com.medlearn.translation.domain.port;

import com.medlearn.shared.LanguageTag;
import com.medlearn.translation.domain.GlossaryTerm.TermKind;

import java.util.List;

/**
 * Proposes glossary terms, with renderings, for a stretch of source text.
 *
 * <p>Only a live implementation exists. Dry runs never extract: their output is not a real
 * translation, and a fake rendering stored in the glossary would be served to every later
 * live run of the book.
 */
public interface TermExtractionPort {

    List<ProposedTerm> propose(Request request);

    /**
     * @param knownSourceTerms terms already in the glossary, so the model proposes only new ones
     * @param maxTerms         upper bound on proposals for this text
     */
    record Request(String text, LanguageTag targetLanguage, List<String> knownSourceTerms, int maxTerms) {
        public Request {
            knownSourceTerms = knownSourceTerms == null ? List.of() : List.copyOf(knownSourceTerms);
        }
    }

    record ProposedTerm(String sourceTerm, String targetTerm, TermKind kind) {}
}
