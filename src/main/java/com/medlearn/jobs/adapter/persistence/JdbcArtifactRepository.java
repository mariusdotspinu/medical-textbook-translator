package com.medlearn.jobs.adapter.persistence;

import com.medlearn.jobs.domain.Artifact;
import com.medlearn.jobs.domain.ArtifactKind;
import com.medlearn.jobs.domain.port.ArtifactRepository;
import com.medlearn.shared.ArtifactId;
import com.medlearn.shared.BlobRef;
import com.medlearn.shared.JobId;
import com.medlearn.shared.TextbookId;
import com.medlearn.shared.port.BlobReferencesPort;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public class JdbcArtifactRepository implements ArtifactRepository, BlobReferencesPort {

    private static final String SELECT = """
            select id, job_id, kind, blob_ref, filename, byte_size, page_count, created_at
              from artifact
            """;

    private final JdbcClient jdbc;

    public JdbcArtifactRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(Artifact artifact) {
        jdbc.sql("""
                        insert into artifact
                            (id, job_id, kind, blob_ref, filename, byte_size, page_count, created_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(
                        artifact.id().value(),
                        artifact.jobId().value(),
                        artifact.kind().name(),
                        artifact.blob().key(),
                        artifact.filename(),
                        artifact.byteSize(),
                        artifact.pageCount().orElse(null),
                        Timestamp.from(artifact.createdAt()))
                .update();
    }

    @Override
    public Optional<Artifact> findById(ArtifactId id) {
        return jdbc.sql(SELECT + " where id = ?").param(id.value()).query(mapper()).optional();
    }

    @Override
    public List<Artifact> findByJob(JobId jobId) {
        return jdbc.sql(SELECT + " where job_id = ? order by created_at")
                .param(jobId.value()).query(mapper()).list();
    }

    @Override
    public List<BlobRef> blobsForTextbook(TextbookId textbookId) {
        return jdbc.sql("""
                        select distinct a.blob_ref
                          from artifact a
                          join job j on j.id = a.job_id
                         where j.textbook_id = ?
                        """)
                .param(textbookId.value())
                .query((rs, rowNum) -> new BlobRef(rs.getString("blob_ref")))
                .list();
    }

    /** Generated PDFs, which the orphan sweep must never delete. */
    @Override
    public Set<BlobRef> referencedBlobs() {
        return Set.copyOf(jdbc.sql("select distinct blob_ref from artifact")
                .query((rs, rowNum) -> new BlobRef(rs.getString("blob_ref")))
                .list());
    }

    @Override
    public boolean isReferenced(BlobRef blob) {
        return jdbc.sql("select exists (select 1 from artifact where blob_ref = ?)")
                .param(blob.key())
                .query(Boolean.class)
                .single();
    }

    private RowMapper<Artifact> mapper() {
        return (ResultSet rs, int rowNum) -> map(rs);
    }

    private static Artifact map(ResultSet rs) throws SQLException {
        // wasNull() reports on the most recently read column, so it must be consulted
        // immediately after getInt - not inside a later argument list, where an intervening
        // read silently changes what it is answering about.
        int rawPageCount = rs.getInt("page_count");
        Optional<Integer> pageCount = rs.wasNull() ? Optional.empty() : Optional.of(rawPageCount);

        return new Artifact(
                new ArtifactId(rs.getObject("id", UUID.class)),
                new JobId(rs.getObject("job_id", UUID.class)),
                ArtifactKind.valueOf(rs.getString("kind")),
                new BlobRef(rs.getString("blob_ref")),
                rs.getString("filename"),
                rs.getLong("byte_size"),
                pageCount,
                rs.getTimestamp("created_at").toInstant());
    }
}
