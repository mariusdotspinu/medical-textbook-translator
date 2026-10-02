package com.medlearn.translation.domain.port;

import com.medlearn.shared.ContentHash;
import com.medlearn.shared.LanguageTag;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Cache of already-translated text, keyed by content.
 *
 * <p>This is what makes retrying, resuming and overlapping chapter ranges nearly free. A
 * translation job that dies halfway and restarts finds its completed blocks already here and
 * skips the API entirely; asking for chapters 1-8 after having translated chapter 1 pays
 * only for chapters 2-8.
 *
 * <p>Keyed by {@code (hash of source text, target language, glossary key)}. The glossary key
 * is the fingerprint of the glossary terms that occur in the block
 * ({@code GlossaryMatcher.fingerprint}): correcting a term invalidates exactly the blocks
 * that use it, and nothing else, so a correction reaches the pages that used the old word
 * without re-paying for the rest of the book.
 */
public interface TranslationMemoryPort {

    /** @return translations already known; missing keys are absent */
    Map<Key, String> findAll(Collection<Key> keys, LanguageTag targetLanguage);

    void putAll(List<Entry> entries, LanguageTag targetLanguage, String model);

    record Key(ContentHash sourceHash, ContentHash glossaryKey) {}

    record Entry(Key key, String targetText) {}
}
