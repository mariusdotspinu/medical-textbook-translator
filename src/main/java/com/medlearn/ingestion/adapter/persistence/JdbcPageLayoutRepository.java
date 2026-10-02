package com.medlearn.ingestion.adapter.persistence;

import com.medlearn.ingestion.domain.PageKind;
import com.medlearn.ingestion.domain.PageLayout;
import com.medlearn.ingestion.domain.port.PageLayoutRepository;
import com.medlearn.shared.PageRange;
import com.medlearn.shared.TextbookId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Stores page layouts as compressed blobs alongside queryable geometry columns.
 *
 * <p>Writes go through {@link JdbcTemplate#batchUpdate} rather than one statement per page:
 * a 1000-page textbook is 1000 inserts, and round-tripping each one individually dominates
 * ingest time. Reads use {@link JdbcClient}, which is pleasanter for mapped queries.
 */
@Repository
public class JdbcPageLayoutRepository implements PageLayoutRepository {

    private static final String UPSERT = """
            insert into page_layout
                (textbook_id, page_no, width, height, kind, char_count, block_count, layout_zstd)
            values (?, ?, ?, ?, ?, ?, ?, ?)
            on conflict (textbook_id, page_no) do update set
                width       = excluded.width,
                height      = excluded.height,
                kind        = excluded.kind,
                char_count  = excluded.char_count,
                block_count = excluded.block_count,
                layout_zstd = excluded.layout_zstd
            """;

    private final JdbcClient jdbcClient;
    private final JdbcTemplate jdbcTemplate;
    private final LayoutCodec codec;

    public JdbcPageLayoutRepository(JdbcClient jdbcClient, JdbcTemplate jdbcTemplate, LayoutCodec codec) {
        this.jdbcClient = jdbcClient;
        this.jdbcTemplate = jdbcTemplate;
        this.codec = codec;
    }

    @Override
    public void saveAll(TextbookId textbookId, List<PageLayout> layouts) {
        if (layouts.isEmpty()) return;

        List<Object[]> batch = new ArrayList<>(layouts.size());
        for (PageLayout layout : layouts) {
            batch.add(new Object[]{
                    textbookId.value(),
                    layout.pageNo(),
                    layout.widthPt(),
                    layout.heightPt(),
                    layout.kind().name(),
                    layout.charCount(),
                    layout.blocks().size(),
                    codec.encode(layout.blocks())
            });
        }
        // Upsert, not insert: makes re-running an interrupted ingest idempotent instead of
        // failing on the pages already written before the interruption.
        jdbcTemplate.batchUpdate(UPSERT, batch);
    }

    @Override
    public Optional<PageLayout> find(TextbookId textbookId, int pageNo) {
        return jdbcClient.sql("""
                        select page_no, width, height, kind, layout_zstd
                          from page_layout
                         where textbook_id = ? and page_no = ?
                        """)
                .params(textbookId.value(), pageNo)
                .query(layoutMapper())
                .optional();
    }

    @Override
    public List<PageLayout> findRange(TextbookId textbookId, PageRange range) {
        return jdbcClient.sql("""
                        select page_no, width, height, kind, layout_zstd
                          from page_layout
                         where textbook_id = ? and page_no between ? and ?
                         order by page_no
                        """)
                .params(textbookId.value(), range.startPage(), range.endPage())
                .query(layoutMapper())
                .list();
    }

    @Override
    public int countPages(TextbookId textbookId) {
        return jdbcClient.sql("select count(*) from page_layout where textbook_id = ?")
                .param(textbookId.value())
                .query(Integer.class)
                .single();
    }

    @Override
    public long totalCharCount(TextbookId textbookId) {
        return jdbcClient.sql(
                        "select coalesce(sum(char_count), 0) from page_layout where textbook_id = ?")
                .param(textbookId.value())
                .query(Long.class)
                .single();
    }

    @Override
    public void deleteAll(TextbookId textbookId) {
        jdbcClient.sql("delete from page_layout where textbook_id = ?")
                .param(textbookId.value())
                .update();
    }

    private RowMapper<PageLayout> layoutMapper() {
        return (ResultSet rs, int rowNum) -> mapRow(rs);
    }

    private PageLayout mapRow(ResultSet rs) throws SQLException {
        return new PageLayout(
                rs.getInt("page_no"),
                rs.getFloat("width"),
                rs.getFloat("height"),
                PageKind.valueOf(rs.getString("kind")),
                codec.decode(rs.getBytes("layout_zstd")));
    }
}
