package com.medlearn.translation.adapter.persistence;

import com.medlearn.platform.compress.ZstdCodec;
import com.medlearn.shared.ContentHash;
import com.medlearn.shared.LanguageTag;
import com.medlearn.translation.domain.port.TranslationMemoryPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Repository
public class JdbcTranslationMemory implements TranslationMemoryPort {

    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;

    public JdbcTranslationMemory(JdbcClient jdbc, JdbcTemplate jdbcTemplate) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Map<Key, String> findAll(Collection<Key> keys, LanguageTag targetLanguage) {
        if (keys.isEmpty()) return Map.of();

        // Two parallel arrays unnested into (source, glossary) pairs: a chapter can carry a
        // thousand keys and the statement should stay one prepared statement regardless.
        List<Key> distinct = keys.stream().distinct().toList();
        String sources = arrayLiteral(distinct.stream().map(Key::sourceHash).toList());
        String glossaries = arrayLiteral(distinct.stream().map(Key::glossaryKey).toList());

        Map<Key, String> found = new HashMap<>();
        jdbc.sql("""
                        select t.source_hash, t.glossary_key, t.target_text_zstd
                          from translation_unit t
                          join unnest(?::char(64)[], ?::char(64)[]) as k(source_hash, glossary_key)
                            on t.source_hash = k.source_hash and t.glossary_key = k.glossary_key
                         where t.target_lang = ?
                        """)
                .params(sources, glossaries, targetLanguage.tag())
                .query((rs, rowNum) -> {
                    found.put(
                            new Key(new ContentHash(rs.getString("source_hash").trim()),
                                    new ContentHash(rs.getString("glossary_key").trim())),
                            new String(ZstdCodec.decompress(rs.getBytes("target_text_zstd")),
                                    StandardCharsets.UTF_8));
                    return null;
                })
                .list();
        return Map.copyOf(found);
    }

    @Override
    public void putAll(List<Entry> entries, LanguageTag targetLanguage, String model) {
        if (entries.isEmpty()) return;

        List<Object[]> batch = new ArrayList<>(entries.size());
        for (Entry entry : entries) {
            batch.add(new Object[]{
                    entry.key().sourceHash().hex(),
                    targetLanguage.tag(),
                    entry.key().glossaryKey().hex(),
                    model,
                    ZstdCodec.compress(entry.targetText().getBytes(StandardCharsets.UTF_8))
            });
        }

        // do nothing on conflict: an identical source under the same glossary terms has the
        // same translation, and a concurrent job may well have written it first.
        jdbcTemplate.batchUpdate("""
                insert into translation_unit
                    (source_hash, target_lang, glossary_key, model, target_text_zstd)
                values (?, ?, ?, ?, ?)
                on conflict (source_hash, target_lang, glossary_key) do nothing
                """, batch);
    }

    private static String arrayLiteral(List<ContentHash> hashes) {
        return hashes.stream().map(ContentHash::hex).collect(Collectors.joining(",", "{", "}"));
    }
}
