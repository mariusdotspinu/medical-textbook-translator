package com.medlearn.translation.domain;

import com.medlearn.shared.ContentHash;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Finds which glossary terms occur in a text.
 *
 * <p>Matches whole words only, so "ventricle" is not found inside "ventricles" and a short
 * term like "MI" is not found inside "MIDDLE". Case-insensitive, except for abbreviations,
 * which must match exactly: "MI" is myocardial infarction, "mi" is not.
 *
 * <p>One alternation pattern for all terms, longest first, so "left ventricle" wins over
 * "ventricle" where both would match.
 */
public final class GlossaryMatcher {

    private static final GlossaryMatcher EMPTY = new GlossaryMatcher(null, Map.of());

    private final Pattern pattern;
    private final Map<String, GlossaryTerm> byLowerSource;

    private GlossaryMatcher(Pattern pattern, Map<String, GlossaryTerm> byLowerSource) {
        this.pattern = pattern;
        this.byLowerSource = byLowerSource;
    }

    public static GlossaryMatcher of(Collection<GlossaryTerm> terms) {
        if (terms.isEmpty()) return EMPTY;

        Map<String, GlossaryTerm> byLower = new HashMap<>();
        for (GlossaryTerm term : terms) byLower.putIfAbsent(lower(term.sourceTerm()), term);

        String alternation = byLower.values().stream()
                .map(GlossaryTerm::sourceTerm)
                .sorted(Comparator.comparingInt(String::length).reversed()
                        .thenComparing(Comparator.naturalOrder()))
                .map(Pattern::quote)
                .collect(Collectors.joining("|"));
        Pattern pattern = Pattern.compile(
                "(?<![\\p{L}\\p{N}])(?:" + alternation + ")(?![\\p{L}\\p{N}])",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        return new GlossaryMatcher(pattern, Map.copyOf(byLower));
    }

    /** The terms that occur in {@code text}, each once. */
    public Set<GlossaryTerm> termsIn(String text) {
        return countsIn(text).keySet();
    }

    /** How many times each term occurs in {@code text}; absent terms are not listed. */
    public Map<GlossaryTerm, Integer> countsIn(String text) {
        if (pattern == null || text == null || text.isEmpty()) return Map.of();
        Map<GlossaryTerm, Integer> counts = new HashMap<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            GlossaryTerm term = byLowerSource.get(lower(matcher.group()));
            if (term == null) continue;
            if (term.kind() == GlossaryTerm.TermKind.ABBREVIATION
                    && !matcher.group().equals(term.sourceTerm())) {
                continue;
            }
            counts.merge(term, 1, Integer::sum);
        }
        return counts;
    }

    /**
     * The glossary part of a translation-memory key: identifies exactly the terms that
     * influence a block's translation.
     *
     * <p>Order-independent, and covers the source and target of each term but not its kind,
     * which does not change the wording. No terms gives the hash of the empty string, which
     * is also the key of text translated before the glossary existed.
     */
    public static ContentHash fingerprint(Collection<GlossaryTerm> terms) {
        String canonical = terms.stream()
                .map(term -> lower(term.sourceTerm()) + '\u0000' + term.targetTerm())
                .sorted()
                .collect(Collectors.joining("\n"));
        return ContentHash.ofText(canonical);
    }

    /** Distinct terms across several texts, in a stable order (by source term). */
    public Set<GlossaryTerm> termsInAll(Collection<String> texts) {
        Set<GlossaryTerm> found = new LinkedHashSet<>();
        texts.forEach(text -> found.addAll(termsIn(text)));
        return found.stream()
                .sorted(Comparator.comparing(GlossaryTerm::sourceTerm))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static String lower(String text) {
        return text.toLowerCase(Locale.ROOT);
    }
}
