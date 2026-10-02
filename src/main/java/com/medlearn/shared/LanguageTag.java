package com.medlearn.shared;

import java.util.Locale;
import java.util.Set;

/**
 * BCP-47 language tag for a translation target, plus the script it is written in.
 *
 * <p>Script is derived from the primary language subtag rather than stored, so callers
 * cannot construct a tag whose script disagrees with its language.
 */
public record LanguageTag(String tag) {

    private static final Set<String> CJK_LANGUAGES = Set.of("zh", "ja", "ko");
    private static final Set<String> CYRILLIC_LANGUAGES =
            Set.of("ru", "bg", "uk", "sr", "mk", "be", "kk");
    private static final Set<String> GREEK_LANGUAGES = Set.of("el");

    public LanguageTag {
        if (tag == null || tag.isBlank()) {
            throw new IllegalArgumentException("language tag is required");
        }
        tag = tag.trim();
        if (!tag.matches("[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*")) {
            throw new IllegalArgumentException("not a BCP-47 language tag: " + tag);
        }
    }

    public static LanguageTag of(String tag) {
        return new LanguageTag(tag);
    }

    /** Primary language subtag, lowercased - {@code "zh"} for {@code "zh-Hans-CN"}. */
    public String primarySubtag() {
        int dash = tag.indexOf('-');
        return (dash < 0 ? tag : tag.substring(0, dash)).toLowerCase(Locale.ROOT);
    }

    public Script script() {
        String lang = primarySubtag();
        if (CJK_LANGUAGES.contains(lang)) return Script.CJK;
        if (CYRILLIC_LANGUAGES.contains(lang)) return Script.CYRILLIC;
        if (GREEK_LANGUAGES.contains(lang)) return Script.GREEK;
        return Script.LATIN;
    }

    public Locale toLocale() {
        return Locale.forLanguageTag(tag);
    }

    /** Endonym where the JVM knows one - what the model is told to translate into. */
    public String displayName() {
        Locale locale = toLocale();
        String name = locale.getDisplayLanguage(locale);
        return name.isBlank() ? tag : name;
    }

    /** English name, for log lines and prompt text that must stay byte-stable. */
    public String englishName() {
        String name = toLocale().getDisplayLanguage(Locale.ENGLISH);
        return name.isBlank() ? tag : name;
    }

    @Override
    public String toString() {
        return tag;
    }
}
