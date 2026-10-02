package com.medlearn.translation.domain;

import com.medlearn.shared.ContentHash;
import com.medlearn.shared.LanguageTag;
import com.medlearn.translation.domain.GlossaryTerm.TermKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GlossaryMatcherTest {

    private static final LanguageTag RO = LanguageTag.of("ro");

    private static GlossaryTerm term(String source, String target) {
        return term(source, target, TermKind.CONDITION);
    }

    private static GlossaryTerm term(String source, String target, TermKind kind) {
        return new GlossaryTerm(null, source, target, RO, kind, 0, false);
    }

    private final GlossaryTerm ventricle = term("ventricle", "ventricul", TermKind.ANATOMY);
    private final GlossaryTerm leftVentricle = term("left ventricle", "ventricul stâng", TermKind.ANATOMY);
    private final GlossaryTerm mi = term("MI", "IM", TermKind.ABBREVIATION);

    @Test
    @DisplayName("whole words only, ignoring case")
    void matchesWholeWordsIgnoringCase() {
        GlossaryMatcher matcher = GlossaryMatcher.of(List.of(ventricle));

        assertThat(matcher.termsIn("The Ventricle contracts.")).containsExactly(ventricle);
        assertThat(matcher.termsIn("Both ventricles contract.")).isEmpty();
        assertThat(matcher.termsIn("intraventricle")).isEmpty();
    }

    @Test
    @DisplayName("the longest term wins where terms overlap")
    void prefersLongestTerm() {
        GlossaryMatcher matcher = GlossaryMatcher.of(List.of(ventricle, leftVentricle));

        assertThat(matcher.countsIn("Left ventricle failure; the ventricle dilates."))
                .containsEntry(leftVentricle, 1)
                .containsEntry(ventricle, 1);
    }

    @Test
    @DisplayName("abbreviations match only in their exact case")
    void abbreviationsAreCaseSensitive() {
        GlossaryMatcher matcher = GlossaryMatcher.of(List.of(mi));

        assertThat(matcher.termsIn("After an acute MI, start a beta blocker.")).containsExactly(mi);
        assertThat(matcher.termsIn("The patient said mi casa.")).isEmpty();
    }

    @Test
    @DisplayName("the memory key ignores order and kind, and changes with a rendering")
    void fingerprintIsStable() {
        ContentHash both = GlossaryMatcher.fingerprint(List.of(ventricle, mi));

        assertThat(GlossaryMatcher.fingerprint(List.of(mi, ventricle))).isEqualTo(both);
        assertThat(GlossaryMatcher.fingerprint(List.of(term("ventricle", "ventricul"), mi)))
                .isEqualTo(both);
        assertThat(GlossaryMatcher.fingerprint(List.of(term("ventricle", "cameră"), mi)))
                .isNotEqualTo(both);
    }

    @Test
    @DisplayName("no terms gives the key text translated before the glossary existed was stored under")
    void emptyFingerprintMatchesMigration() {
        assertThat(GlossaryMatcher.fingerprint(List.of()).hex())
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
                .isEqualTo(ContentHash.ofText("").hex());
        assertThat(GlossaryMatcher.of(List.of()).termsIn("anything")).isEmpty();
    }
}
