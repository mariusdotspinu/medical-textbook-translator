package com.medlearn.translation.domain;

import com.medlearn.shared.Script;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.ToDoubleFunction;

import static org.assertj.core.api.Assertions.assertThat;

class ScriptAwareLineBreakerTest {

    /** Every character one point wide, so widths are trivially predictable. */
    private static final ToDoubleFunction<String> UNIT_WIDTH = String::length;

    @Test
    @DisplayName("Latin text breaks between words, never inside them")
    void breaksLatinOnWordBoundaries() {
        List<String> lines = ScriptAwareLineBreaker.wrap(
                "acute myocardial infarction confirmed", Script.LATIN, 20, UNIT_WIDTH);

        assertThat(lines).isNotEmpty();
        assertThat(lines).allSatisfy(line -> assertThat(line.length()).isLessThanOrEqualTo(20));
        // No word may be split across lines.
        assertThat(String.join(" ", lines)).isEqualTo("acute myocardial infarction confirmed");
    }

    @Test
    @DisplayName("CJK breaks between characters, since there are no spaces to break on")
    void breaksCjkOnCharacters() {
        String chinese = "急性心肌梗死的诊断和治疗方法";
        List<String> lines = ScriptAwareLineBreaker.wrap(chinese, Script.CJK, 5, UNIT_WIDTH);

        assertThat(lines.size()).isGreaterThan(1);
        assertThat(String.join("", lines)).isEqualTo(chinese);
        assertThat(lines).allSatisfy(line -> assertThat(line.length()).isLessThanOrEqualTo(5));
    }

    @Test
    @DisplayName("a CJK line never begins with closing punctuation")
    void appliesKinsokuRule() {
        // Starting a line with a closing bracket or full stop reads as an error to anyone who
        // reads the language, so such a character is pulled back onto the previous line.
        String text = "心肌梗死。诊断方法。治疗。";
        List<String> lines = ScriptAwareLineBreaker.wrap(text, Script.CJK, 4, UNIT_WIDTH);

        assertThat(lines).allSatisfy(line ->
                assertThat("、。，．：；！？）］｝".indexOf(line.charAt(0))).isLessThan(0));
    }

    @Test
    @DisplayName("a single word wider than the measure is split rather than left to overflow")
    void splitsOverlongWords() {
        List<String> lines = ScriptAwareLineBreaker.wrap(
                "methylenetetrahydrofolatereductase", Script.LATIN, 10, UNIT_WIDTH);

        assertThat(lines).allSatisfy(line -> assertThat(line.length()).isLessThanOrEqualTo(10));
        assertThat(String.join("", lines)).isEqualTo("methylenetetrahydrofolatereductase");
    }

    @Test
    void handlesBlankInput() {
        assertThat(ScriptAwareLineBreaker.wrap("", Script.LATIN, 50, UNIT_WIDTH)).isEmpty();
        assertThat(ScriptAwareLineBreaker.wrap(null, Script.LATIN, 50, UNIT_WIDTH)).isEmpty();
    }
}
