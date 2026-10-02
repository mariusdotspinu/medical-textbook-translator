package com.medlearn.translation.adapter.persistence;

import com.medlearn.shared.LanguageTag;
import com.medlearn.shared.PageRange;
import com.medlearn.shared.TextbookId;
import com.medlearn.translation.domain.GlossaryTerm;
import com.medlearn.translation.domain.GlossaryTerm.TermKind;
import com.medlearn.translation.domain.GlossaryTermId;
import com.medlearn.translation.domain.port.GlossaryRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public class JdbcGlossaryRepository implements GlossaryRepository {

    private static final String SELECT = """
            select id, source_term, target_term, target_lang, kind, occurrences, pinned
              from glossary_term
            """;

    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;

    public JdbcGlossaryRepository(JdbcClient jdbc, JdbcTemplate jdbcTemplate) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<GlossaryTerm> findAll(TextbookId textbookId, LanguageTag language) {
        return jdbc.sql(SELECT + " where textbook_id = ? and target_lang = ? order by lower(source_term)")
                .params(textbookId.value(), language.tag())
                .query(mapper())
                .list();
    }

    @Override
    public Optional<GlossaryTerm> findBySource(
            TextbookId textbookId, LanguageTag language, String sourceTerm) {
        return jdbc.sql(SELECT + " where textbook_id = ? and target_lang = ? and lower(source_term) = lower(?)")
                .params(textbookId.value(), language.tag(), sourceTerm.strip())
                .query(mapper())
                .optional();
    }

    @Override
    public void addNew(TextbookId textbookId, List<GlossaryTerm> terms) {
        if (terms.isEmpty()) return;
        // Conflict on the case-insensitive unique index: an existing term always wins, so a
        // rendering pages already use is never replaced behind the reader's back.
        jdbcTemplate.batchUpdate("""
                        insert into glossary_term
                            (id, textbook_id, target_lang, source_term, target_term, kind, occurrences, pinned)
                        values (?, ?, ?, ?, ?, ?, ?, ?)
                        on conflict (textbook_id, target_lang, lower(source_term)) do nothing
                        """,
                terms.stream().map(term -> new Object[]{
                        term.id().value(), textbookId.value(), term.targetLanguage().tag(),
                        term.sourceTerm(), term.targetTerm(), term.kind().name(),
                        term.occurrences(), term.pinned()}).toList());
    }

    @Override
    public GlossaryTerm save(TextbookId textbookId, GlossaryTerm term) {
        jdbc.sql("""
                        insert into glossary_term
                            (id, textbook_id, target_lang, source_term, target_term, kind, occurrences, pinned)
                        values (?, ?, ?, ?, ?, ?, ?, ?)
                        on conflict (textbook_id, target_lang, lower(source_term)) do update
                           set target_term = excluded.target_term,
                               kind        = excluded.kind,
                               pinned      = excluded.pinned
                        """)
                .params(term.id().value(), textbookId.value(), term.targetLanguage().tag(),
                        term.sourceTerm(), term.targetTerm(), term.kind().name(),
                        term.occurrences(), term.pinned())
                .update();
        return findBySource(textbookId, term.targetLanguage(), term.sourceTerm()).orElseThrow();
    }

    @Override
    public void addOccurrences(
            TextbookId textbookId, LanguageTag language, Map<String, Integer> bySourceTerm) {
        if (bySourceTerm.isEmpty()) return;
        jdbcTemplate.batchUpdate("""
                        update glossary_term set occurrences = occurrences + ?
                         where textbook_id = ? and target_lang = ? and lower(source_term) = lower(?)
                        """,
                bySourceTerm.entrySet().stream().map(entry -> new Object[]{
                        entry.getValue(), textbookId.value(), language.tag(), entry.getKey()}).toList());
    }

    @Override
    public boolean delete(TextbookId textbookId, LanguageTag language, GlossaryTermId id) {
        return jdbc.sql("delete from glossary_term where id = ? and textbook_id = ? and target_lang = ?")
                .params(id.value(), textbookId.value(), language.tag())
                .update() > 0;
    }

    @Override
    public Set<Integer> coveredPages(TextbookId textbookId, LanguageTag language, PageRange range) {
        return new HashSet<>(jdbc.sql("""
                        select page_no from glossary_coverage
                         where textbook_id = ? and target_lang = ? and page_no between ? and ?
                        """)
                .params(textbookId.value(), language.tag(), range.startPage(), range.endPage())
                .query(Integer.class)
                .list());
    }

    @Override
    public void markCovered(TextbookId textbookId, LanguageTag language, Collection<Integer> pages) {
        if (pages.isEmpty()) return;
        jdbcTemplate.batchUpdate("""
                        insert into glossary_coverage (textbook_id, target_lang, page_no)
                        values (?, ?, ?)
                        on conflict do nothing
                        """,
                pages.stream().map(page -> new Object[]{
                        textbookId.value(), language.tag(), page}).toList());
    }

    private static RowMapper<GlossaryTerm> mapper() {
        return (rs, rowNum) -> new GlossaryTerm(
                new GlossaryTermId(rs.getObject("id", UUID.class)),
                rs.getString("source_term"),
                rs.getString("target_term"),
                LanguageTag.of(rs.getString("target_lang")),
                TermKind.valueOf(rs.getString("kind")),
                rs.getInt("occurrences"),
                rs.getBoolean("pinned"));
    }
}
