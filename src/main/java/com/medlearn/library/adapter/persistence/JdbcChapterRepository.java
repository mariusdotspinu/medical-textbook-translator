package com.medlearn.library.adapter.persistence;

import com.medlearn.library.domain.Chapter;
import com.medlearn.library.domain.port.ChapterRepository;
import com.medlearn.shared.ChapterId;
import com.medlearn.shared.PageRange;
import com.medlearn.shared.TextbookId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcChapterRepository implements ChapterRepository {

    private static final String SELECT_COLUMNS = """
            select id, textbook_id, parent_id, ordinal, level, title, start_page, end_page
              from chapter
            """;

    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;

    public JdbcChapterRepository(JdbcClient jdbc, JdbcTemplate jdbcTemplate) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public void replaceAll(TextbookId textbookId, List<Chapter> chapters) {
        deleteAll(textbookId);
        if (chapters.isEmpty()) return;

        // parent_id is a self-referencing FK, so shallower nodes must be inserted first.
        // Sorting by level guarantees a parent exists before any of its children.
        List<Chapter> ordered = chapters.stream()
                .sorted(Comparator.comparingInt(Chapter::level).thenComparingInt(Chapter::ordinal))
                .toList();

        List<Object[]> batch = new ArrayList<>(ordered.size());
        for (Chapter chapter : ordered) {
            batch.add(new Object[]{
                    chapter.id().value(),
                    chapter.textbookId().value(),
                    chapter.parentId().map(ChapterId::value).orElse(null),
                    chapter.ordinal(),
                    chapter.level(),
                    chapter.title(),
                    chapter.range().startPage(),
                    chapter.range().endPage()
            });
        }
        jdbcTemplate.batchUpdate("""
                insert into chapter
                    (id, textbook_id, parent_id, ordinal, level, title, start_page, end_page)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """, batch);
    }

    @Override
    public List<Chapter> findByTextbook(TextbookId textbookId) {
        return jdbc.sql(SELECT_COLUMNS + " where textbook_id = ? order by ordinal")
                .param(textbookId.value())
                .query(mapper())
                .list();
    }

    @Override
    public Optional<Chapter> findById(ChapterId id) {
        return jdbc.sql(SELECT_COLUMNS + " where id = ?")
                .param(id.value())
                .query(mapper())
                .optional();
    }

    @Override
    public List<Chapter> findAllById(Collection<ChapterId> ids) {
        if (ids.isEmpty()) return List.of();
        // A Postgres array literal cast server-side keeps this one statement regardless of
        // how many chapters the reader selected, without relying on the driver inferring a
        // uuid[] from a Java UUID[] - which it does not do dependably.
        String arrayLiteral = ids.stream()
                .map(id -> id.value().toString())
                .collect(java.util.stream.Collectors.joining(",", "{", "}"));
        return jdbc.sql(SELECT_COLUMNS + " where id = any(?::uuid[]) order by ordinal")
                .param(arrayLiteral)
                .query(mapper())
                .list();
    }

    @Override
    public void deleteAll(TextbookId textbookId) {
        jdbc.sql("delete from chapter where textbook_id = ?")
                .param(textbookId.value())
                .update();
    }

    private RowMapper<Chapter> mapper() {
        return (ResultSet rs, int rowNum) -> map(rs);
    }

    private static Chapter map(ResultSet rs) throws SQLException {
        UUID parent = rs.getObject("parent_id", UUID.class);
        return new Chapter(
                new ChapterId(rs.getObject("id", UUID.class)),
                new TextbookId(rs.getObject("textbook_id", UUID.class)),
                Optional.ofNullable(parent).map(ChapterId::new),
                rs.getInt("ordinal"),
                rs.getInt("level"),
                rs.getString("title"),
                PageRange.of(rs.getInt("start_page"), rs.getInt("end_page")));
    }
}
