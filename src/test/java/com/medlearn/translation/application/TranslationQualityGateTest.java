package com.medlearn.translation.application;

import com.medlearn.platform.config.TranslationProperties;
import com.medlearn.translation.domain.QualityFinding;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The checks that stand between a mistranslated dose and a doctor reading it.
 */
class TranslationQualityGateTest {

    private final TranslationQualityGate gate =
            new TranslationQualityGate(new TranslationProperties(24_000, 8, 0.4, 2.5));

    @Test
    @DisplayName("a changed dose is caught")
    void catchesAlteredDosage() {
        List<QualityFinding> findings = gate.inspect("p1-b0",
                "Administer aspirin 300 mg orally.",
                "Administrati aspirina 30 mg oral.");

        assertThat(findings).hasAtLeastOneElementOfType(QualityFinding.NumericMismatch.class);
        assertThat(findings.getFirst().worthRetrying()).isTrue();
    }

    @Test
    @DisplayName("a correct translation with the same numbers passes")
    void acceptsFaithfulTranslation() {
        assertThat(gate.inspect("p1-b0",
                "Administer aspirin 300 mg orally within 4 hours.",
                "Administrati aspirina 300 mg oral in 4 ore.")).isEmpty();
    }

    @Test
    @DisplayName("a comma decimal separator is a correct translation, not a changed number")
    void toleratesDecimalSeparatorChange() {
        // Romanian and German write 0,5 where English writes 0.5. Flagging that would make
        // the check noisy enough to be ignored, which is worse than not having it.
        assertThat(gate.inspect("p1-b0",
                "Give 0.5 mg of atropine.",
                "Administrati 0,5 mg de atropina.")).isEmpty();
    }

    @Test
    @DisplayName("reordering a sentence does not count as changing its numbers")
    void toleratesWordOrderChange() {
        assertThat(gate.inspect("p1-b0",
                "After 3 days, increase to 10 mg.",
                "Cresteti la 10 mg dupa 3 zile.")).isEmpty();
    }

    @Test
    @DisplayName("an empty translation is caught and nothing else is reported")
    void catchesEmptyTranslation() {
        List<QualityFinding> findings = gate.inspect("p1-b0", "Some source text.", "   ");
        assertThat(findings).singleElement().isInstanceOf(QualityFinding.EmptyTranslation.class);
    }

    @Test
    @DisplayName("truncation is caught by length ratio")
    void catchesTruncation() {
        String source = "The left anterior descending artery supplies the anterior two-thirds "
                + "of the interventricular septum and the apex of the left ventricle.";
        assertThat(gate.inspect("p1-b0", source, "Artera."))
                .hasAtLeastOneElementOfType(QualityFinding.ImplausibleLength.class);
    }

    @Test
    @DisplayName("a lost do-not-translate placeholder is caught")
    void catchesUnrestoredPlaceholder() {
        assertThat(gate.inspect("p1-b0",
                "Treat with ␠DRUG1␠ immediately.",
                "Tratati imediat."))
                .hasAtLeastOneElementOfType(QualityFinding.UnrestoredPlaceholder.class);
    }
}
