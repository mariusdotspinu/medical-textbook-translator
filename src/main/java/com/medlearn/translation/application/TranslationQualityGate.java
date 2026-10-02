package com.medlearn.translation.application;

import com.medlearn.platform.config.TranslationProperties;
import com.medlearn.translation.domain.QualityFinding;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic checks on every translated block, costing nothing.
 *
 * <p>These run before anything is cached or rendered, and they catch the failures that
 * matter most in a medical textbook without asking a model to grade itself.
 *
 * <p>The numeric check is the important one. A translation that silently changes "300 mg" to
 * "30 mg" reads perfectly and is a clinical hazard; no amount of fluency review would catch
 * it, but comparing the numbers in the source against the numbers in the output catches it
 * every time.
 */
@Component
public class TranslationQualityGate {

    /** Integers and decimals, with either decimal separator. */
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:[.,]\\d+)?");

    /** Placeholders the pre-mask inserts around do-not-translate spans. */
    private static final Pattern PLACEHOLDER = Pattern.compile("␠[A-Z0-9]{2,8}␠");

    private final TranslationProperties properties;

    public TranslationQualityGate(TranslationProperties properties) {
        this.properties = properties;
    }

    public List<QualityFinding> inspect(String blockId, String source, String target) {
        List<QualityFinding> findings = new ArrayList<>(2);

        if (target == null || target.isBlank()) {
            findings.add(new QualityFinding.EmptyTranslation(blockId));
            return findings;          // nothing else is meaningful on an empty string
        }

        checkNumbers(blockId, source, target).ifPresent(findings::add);
        checkLength(blockId, source, target).ifPresent(findings::add);
        checkPlaceholders(blockId, source, target).ifPresent(findings::add);

        return List.copyOf(findings);
    }

    /**
     * Compares the multiset of numbers, not just their presence.
     *
     * <p>A multiset, because "give 2 tablets 3 times daily" and "give 3 tablets 2 times
     * daily" contain the same numbers but are different instructions - though order is not
     * compared, since word order legitimately changes in translation. Decimal separators are
     * normalised first: Romanian and German write 0,5 where English writes 0.5, and that is
     * a correct translation, not a corrupted dose.
     */
    private java.util.Optional<QualityFinding> checkNumbers(
            String blockId, String source, String target) {

        List<String> sourceNumbers = numbersIn(source);
        List<String> targetNumbers = numbersIn(target);

        if (sourceNumbers.equals(targetNumbers)) return java.util.Optional.empty();

        return java.util.Optional.of(new QualityFinding.NumericMismatch(
                blockId, String.join(", ", sourceNumbers), String.join(", ", targetNumbers)));
    }

    private static List<String> numbersIn(String text) {
        List<String> numbers = new ArrayList<>();
        Matcher matcher = NUMBER.matcher(text);
        while (matcher.find()) {
            numbers.add(matcher.group().replace(',', '.'));
        }
        // Sorted so that a legitimate reordering of the sentence is not reported as a change.
        return numbers.stream().sorted().toList();
    }

    /** Catches truncation and runaway padding, both of which read plausibly in isolation. */
    private java.util.Optional<QualityFinding> checkLength(
            String blockId, String source, String target) {

        if (source.isBlank()) return java.util.Optional.empty();
        double ratio = (double) target.length() / source.length();

        if (ratio < properties.lengthRatioMin() || ratio > properties.lengthRatioMax()) {
            return java.util.Optional.of(new QualityFinding.ImplausibleLength(blockId, ratio));
        }
        return java.util.Optional.empty();
    }

    /** Anything the do-not-translate mask protected must come back intact. */
    private java.util.Optional<QualityFinding> checkPlaceholders(
            String blockId, String source, String target) {

        Matcher matcher = PLACEHOLDER.matcher(source);
        while (matcher.find()) {
            String placeholder = matcher.group();
            if (!target.contains(placeholder)) {
                return java.util.Optional.of(
                        new QualityFinding.UnrestoredPlaceholder(blockId, placeholder));
            }
        }
        return java.util.Optional.empty();
    }
}
