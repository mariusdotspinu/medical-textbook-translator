package com.medlearn.translation.domain;

import com.medlearn.shared.Script;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.function.ToDoubleFunction;

/**
 * Wraps translated text to a measured width, by script.
 *
 * <p>Latin, Cyrillic and Greek break between words; CJK has no inter-word spaces and breaks
 * between characters. Applying word breaking to Chinese yields one unbreakable line that
 * overflows every box, and applying character breaking to Romanian splits words mid-stem.
 *
 * <p>Measurement is injected rather than computed here so this stays pure domain logic: the
 * caller passes a width function backed by whatever font it is about to draw with.
 */
public final class ScriptAwareLineBreaker {

    /**
     * Characters that must not begin a line in CJK typesetting (a reduced kinsoku set).
     * Without this, a line can start with a stray closing bracket or comma, which reads as
     * an error to anyone who reads the language.
     */
    private static final String CJK_NO_LINE_START = "、。，．：；！？）］｝〉》」』】"
            + ",.:;!?)]}、。";

    private ScriptAwareLineBreaker() {}

    /**
     * @param text        text to wrap
     * @param script      writing system of the target language
     * @param maxWidth    available width in points
     * @param widthOf     measures a string in points at the intended size
     * @return wrapped lines; never empty for non-blank input
     */
    public static List<String> wrap(
            String text, Script script, float maxWidth, ToDoubleFunction<String> widthOf) {

        if (text == null || text.isBlank()) return List.of();
        if (maxWidth <= 0) return List.of(text);

        return script.lineBreaking() == Script.LineBreaking.CHARACTER
                ? wrapByCharacter(text, maxWidth, widthOf)
                : wrapByWord(text, maxWidth, widthOf);
    }

    private static List<String> wrapByWord(
            String text, float maxWidth, ToDoubleFunction<String> widthOf) {

        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();

        BreakIterator words = BreakIterator.getLineInstance();
        words.setText(text);

        int start = words.first();
        for (int end = words.next(); end != BreakIterator.DONE; start = end, end = words.next()) {
            String chunk = text.substring(start, end);
            String candidate = line + chunk;

            if (widthOf.applyAsDouble(candidate.stripTrailing()) <= maxWidth || line.isEmpty()) {
                line.append(chunk);
            } else {
                lines.add(line.toString().stripTrailing());
                line = new StringBuilder(chunk.stripLeading());
            }
        }
        if (!line.isEmpty()) lines.add(line.toString().stripTrailing());

        // A single word longer than the measure - a long chemical name, a URL - still has to
        // go somewhere, so split it rather than let it run off the page.
        return splitOverlongLines(lines, maxWidth, widthOf);
    }

    private static List<String> wrapByCharacter(
            String text, float maxWidth, ToDoubleFunction<String> widthOf) {

        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();

        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
            String candidate = line.toString() + character;

            if (widthOf.applyAsDouble(candidate) <= maxWidth || line.isEmpty()) {
                line.append(character);
                continue;
            }

            // Never start the next line with closing punctuation: pull it onto this line.
            if (CJK_NO_LINE_START.indexOf(character) >= 0) {
                line.append(character);
                lines.add(line.toString());
                line = new StringBuilder();
                continue;
            }

            lines.add(line.toString());
            line = new StringBuilder().append(character);
        }
        if (!line.isEmpty()) lines.add(line.toString());
        return List.copyOf(lines);
    }

    private static List<String> splitOverlongLines(
            List<String> lines, float maxWidth, ToDoubleFunction<String> widthOf) {

        List<String> result = new ArrayList<>(lines.size());
        for (String line : lines) {
            if (widthOf.applyAsDouble(line) <= maxWidth || line.length() <= 1) {
                result.add(line);
                continue;
            }
            StringBuilder piece = new StringBuilder();
            for (int i = 0; i < line.length(); i++) {
                if (!piece.isEmpty()
                        && widthOf.applyAsDouble(piece.toString() + line.charAt(i)) > maxWidth) {
                    result.add(piece.toString());
                    piece = new StringBuilder();
                }
                piece.append(line.charAt(i));
            }
            if (!piece.isEmpty()) result.add(piece.toString());
        }
        return List.copyOf(result);
    }
}
