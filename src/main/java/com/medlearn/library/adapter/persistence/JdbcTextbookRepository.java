package com.medlearn.library.adapter.persistence;

import com.medlearn.library.domain.ChapterSource;
import com.medlearn.library.domain.Textbook;
import com.medlearn.library.domain.TextbookStatus;
import com.medlearn.library.domain.port.TextbookRepository;
import com.medlearn.shared.BlobRef;
import com.medlearn.shared.ContentHash;
import com.medlearn.shared.LanguageTag;
import com.medlearn.shared.TextbookId;
import com.medlearn.shared.port.BlobReferencesPort;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public class JdbcTextbookRepository implements TextbookRepository, BlobReferencesPort {

    private static final String SELECT_COLUMNS = """
            select id, title, authors, source_language, page_count, byte_size, sha256,
                   blob_ref, status, chapter_source, uploaded_at, ingested_at
              from textbook
            """;

    private final JdbcClient jdbc;

    public JdbcTextbookRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(Textbook textbook) {
        jdbc.sql("""
                        insert into textbook
                            (id, title, authors, source_language, page_count, byte_size, sha256,
                             blob_ref, status, chapter_source, uploaded_at, ingested_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(
                        textbook.id().value(),
                        textbook.title(),
                        textbook.authors().orElse(null),
                        textbook.sourceLanguage().map(LanguageTag::tag).orElse(null),
                        textbook.pageCount(),
                        textbook.byteSize(),
                        textbook.hash().hex(),
                        textbook.blob().key(),
                        textbook.status().name(),
                        textbook.chapterSource().name(),
                        Timestamp.from(textbook.uploadedAt()),
                        textbook.ingestedAt().map(Timestamp::from).orElse(null))
                .update();
    }

    @Override
    public Optional<Textbook> findById(TextbookId id) {
        return jdbc.sql(SELECT_COLUMNS + " where id = ?")
                .param(id.value())
                .query(mapper())
                .optional();
    }

    @Override
    public Optional<Textbook> findByHash(ContentHash hash) {
        return jdbc.sql(SELECT_COLUMNS + " where sha256 = ?")
                .param(hash.hex())
                .query(mapper())
                .optional();
    }

    @Override
    public List<Textbook> findAll() {
        return jdbc.sql(SELECT_COLUMNS + " order by uploaded_at desc")
                .query(mapper())
                .list();
    }

    @Override
    public void updateStatus(TextbookId id, TextbookStatus status) {
        jdbc.sql("update textbook set status = ? where id = ?")
                .params(status.name(), id.value())
                .update();
    }

    @Override
    public void markReady(TextbookId id, ChapterSource source, LanguageTag detectedLanguage, int pageCount) {
        jdbc.sql("""
                        update textbook
                           set status         = ?,
                               chapter_source = ?,
                               source_language = coalesce(?, source_language),
                               page_count     = ?,
                               ingested_at    = ?
                         where id = ?
                        """)
                .params(
                        TextbookStatus.READY.name(),
                        source.name(),
                        detectedLanguage == null ? null : detectedLanguage.tag(),
                        pageCount,
                        Timestamp.from(Instant.now()),
                        id.value())
                .update();
    }

    /** Uploaded originals, which the orphan sweep must never delete. */
    @Override
    public Set<BlobRef> referencedBlobs() {
        return Set.copyOf(jdbc.sql("select blob_ref from textbook")
                .query((rs, rowNum) -> new BlobRef(rs.getString("blob_ref")))
                .list());
    }

    @Override
    public int failUnfinishedIngestion() {
        return jdbc.sql("update textbook set status = ? where status in (?, ?)")
                .params(TextbookStatus.FAILED.name(),
                        TextbookStatus.UPLOADED.name(), TextbookStatus.EXTRACTING.name())
                .update();
    }

    @Override
    public void delete(TextbookId id) {
        // page_layout, chapter, job and artifact all cascade from here.
        jdbc.sql("delete from textbook where id = ?").param(id.value()).update();
    }

    private RowMapper<Textbook> mapper() {
        return (ResultSet rs, int rowNum) -> map(rs);
    }

    private static Textbook map(ResultSet rs) throws SQLException {
        String language = rs.getString("source_language");
        Timestamp ingested = rs.getTimestamp("ingested_at");
        return new Textbook(
                new TextbookId(rs.getObject("id", java.util.UUID.class)),
                rs.getString("title"),
                Optional.ofNullable(rs.getString("authors")),
                Optional.ofNullable(language).map(LanguageTag::of),
                rs.getInt("page_count"),
                rs.getLong("byte_size"),
                new ContentHash(rs.getString("sha256").trim()),
                new BlobRef(rs.getString("blob_ref")),
                TextbookStatus.valueOf(rs.getString("status")),
                ChapterSource.valueOf(rs.getString("chapter_source")),
                rs.getTimestamp("uploaded_at").toInstant(),
                Optional.ofNullable(ingested).map(Timestamp::toInstant));
    }
}
