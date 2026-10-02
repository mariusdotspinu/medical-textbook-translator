package com.medlearn.jobs.adapter.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medlearn.jobs.domain.Job;
import com.medlearn.jobs.domain.JobFinding;
import com.medlearn.jobs.domain.JobState;
import com.medlearn.jobs.domain.JobType;
import com.medlearn.jobs.domain.port.JobRepository;
import com.medlearn.shared.ChapterId;
import com.medlearn.shared.JobId;
import com.medlearn.shared.LanguageTag;
import com.medlearn.shared.TextbookId;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
public class JdbcJobRepository implements JobRepository {

    private static final String SELECT_COLUMNS = """
            select id, textbook_id, type, target_lang, chapter_ids, options, state, done_units,
                   total_units, phase, error, created_at, started_at, finished_at,
                   jsonb_array_length(coalesce(findings, '[]'::jsonb)) as finding_count
              from job
            """;

    private static final TypeReference<List<JobFinding>> FINDING_LIST = new TypeReference<>() {};
    private static final TypeReference<Map<String, String>> OPTIONS = new TypeReference<>() {};

    /**
     * Jackson 2 with its own mapper, as in {@code LayoutCodec}: {@code job.findings} and
     * {@code job.options} are stored formats whose shape must not drift because someone
     * retunes Spring's web mapper.
     */
    private static final ObjectMapper JSON = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private final JdbcClient jdbc;

    public JdbcJobRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(Job job) {
        jdbc.sql("""
                        insert into job
                            (id, textbook_id, type, target_lang, chapter_ids, options, state,
                             done_units, total_units, phase, created_at)
                        values (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, 0, 0, null, ?)
                        """)
                .params(
                        job.id().value(),
                        job.textbookId().map(TextbookId::value).orElse(null),
                        job.type().name(),
                        job.targetLanguage().map(LanguageTag::tag).orElse(null),
                        toJsonArray(job.chapterIds()),
                        writeJson(job.options()),
                        job.state().persistedName(),
                        Timestamp.from(job.createdAt()))
                .update();
    }

    @Override
    public Optional<Job> findById(JobId id) {
        return jdbc.sql(SELECT_COLUMNS + " where id = ?")
                .param(id.value())
                .query(mapper())
                .optional();
    }

    @Override
    public List<Job> findByTextbook(TextbookId textbookId) {
        return jdbc.sql(SELECT_COLUMNS + " where textbook_id = ? order by created_at desc")
                .param(textbookId.value())
                .query(mapper())
                .list();
    }

    /**
     * Exhaustive over {@link JobState}: each variant writes exactly the columns it owns.
     * A new state cannot be added without the compiler demanding its persistence here.
     */
    @Override
    public void updateState(JobId id, JobState state) {
        switch (state) {
            case JobState.Pending ignored -> jdbc.sql("""
                            update job set state = ?, error = null, finished_at = null
                             where id = ?
                            """)
                    .params(state.persistedName(), id.value())
                    .update();

            case JobState.Running running -> jdbc.sql("""
                            update job
                               set state       = ?,
                                   done_units  = ?,
                                   total_units = ?,
                                   phase       = ?,
                                   started_at  = coalesce(started_at, ?)
                             where id = ?
                            """)
                    .params(state.persistedName(), running.doneUnits(), running.totalUnits(),
                            running.phase(), Timestamp.from(Instant.now()), id.value())
                    .update();

            case JobState.Succeeded succeeded -> jdbc.sql("""
                            update job
                               set state       = ?,
                                   done_units  = ?,
                                   total_units = ?,
                                   error       = null,
                                   finished_at = ?
                             where id = ?
                            """)
                    .params(state.persistedName(), succeeded.totalUnits(), succeeded.totalUnits(),
                            Timestamp.from(succeeded.finishedAt()), id.value())
                    .update();

            case JobState.Failed failed -> jdbc.sql("""
                            update job set state = ?, error = ?, finished_at = ? where id = ?
                            """)
                    .params(state.persistedName(), failed.message(),
                            Timestamp.from(failed.finishedAt()), id.value())
                    .update();

            case JobState.Cancelled cancelled -> jdbc.sql("""
                            update job set state = ?, finished_at = ? where id = ?
                            """)
                    .params(state.persistedName(), Timestamp.from(cancelled.finishedAt()), id.value())
                    .update();
        }
    }

    @Override
    public void updateProgress(JobId id, int doneUnits, int totalUnits, String phase) {
        jdbc.sql("update job set done_units = ?, total_units = ?, phase = ? where id = ?")
                .params(doneUnits, totalUnits, phase, id.value())
                .update();
    }

    @Override
    public int failUnfinished(String message, Instant finishedAt) {
        return jdbc.sql("""
                        update job set state = 'FAILED', error = ?, finished_at = ?
                         where state in ('PENDING', 'RUNNING')
                        """)
                .params(message, Timestamp.from(finishedAt))
                .update();
    }

    @Override
    public int deleteByTextbook(TextbookId textbookId) {
        // artifact rows cascade from job.
        return jdbc.sql("delete from job where textbook_id = ?").param(textbookId.value()).update();
    }

    @Override
    public void appendFindings(JobId id, List<JobFinding> findings) {
        if (findings.isEmpty()) return;
        // jsonb concatenation appends in the database, so a stage never has to read back
        // and rewrite what an earlier stage recorded.
        jdbc.sql("update job set findings = coalesce(findings, '[]'::jsonb) || ?::jsonb where id = ?")
                .params(writeFindings(findings), id.value())
                .update();
    }

    @Override
    public List<JobFinding> findFindings(JobId id) {
        return jdbc.sql("select findings from job where id = ?")
                .param(id.value())
                .query((rs, rowNum) -> readFindings(rs.getString("findings")))
                .optional()
                .orElse(List.of());
    }

    private static String writeFindings(List<JobFinding> findings) {
        return writeJson(findings);
    }

    private static String writeJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise job data", e);
        }
    }

    private static Map<String, String> readOptions(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return JSON.readValue(json, OPTIONS);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not read job options", e);
        }
    }

    private static List<JobFinding> readFindings(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return JSON.readValue(json, FINDING_LIST);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not read job findings", e);
        }
    }

    private RowMapper<Job> mapper() {
        return (ResultSet rs, int rowNum) -> map(rs);
    }

    private static Job map(ResultSet rs) throws SQLException {
        UUID textbookId = rs.getObject("textbook_id", UUID.class);
        String language = rs.getString("target_lang");
        Timestamp startedAt = rs.getTimestamp("started_at");
        Timestamp finishedAt = rs.getTimestamp("finished_at");

        return new Job(
                new JobId(rs.getObject("id", UUID.class)),
                Optional.ofNullable(textbookId).map(TextbookId::new),
                JobType.valueOf(rs.getString("type")),
                Optional.ofNullable(language).map(LanguageTag::of),
                parseJsonArray(rs.getString("chapter_ids")),
                readOptions(rs.getString("options")),
                readState(rs),
                rs.getInt("finding_count"),
                rs.getTimestamp("created_at").toInstant(),
                Optional.ofNullable(startedAt).map(Timestamp::toInstant),
                Optional.ofNullable(finishedAt).map(Timestamp::toInstant));
    }

    private static JobState readState(ResultSet rs) throws SQLException {
        String name = rs.getString("state");
        Timestamp finishedAt = rs.getTimestamp("finished_at");
        Instant finished = finishedAt == null ? Instant.now() : finishedAt.toInstant();

        return switch (name) {
            case "PENDING" -> new JobState.Pending();
            case "RUNNING" -> new JobState.Running(
                    rs.getInt("done_units"), rs.getInt("total_units"), rs.getString("phase"));
            case "SUCCEEDED" -> new JobState.Succeeded(finished, rs.getInt("total_units"));
            case "FAILED" -> new JobState.Failed(finished, rs.getString("error"));
            case "CANCELLED" -> new JobState.Cancelled(finished);
            default -> throw new IllegalStateException("Unknown persisted job state: " + name);
        };
    }

    /** Hand-rolled rather than pulling a JSON mapper in for a list of UUID strings. */
    private static String toJsonArray(List<ChapterId> ids) {
        return ids.stream()
                .map(id -> '"' + id.value().toString() + '"')
                .collect(Collectors.joining(",", "[", "]"));
    }

    private static List<ChapterId> parseJsonArray(String json) {
        if (json == null || json.isBlank()) return List.of();
        String trimmed = json.trim();
        if (trimmed.length() <= 2) return List.of();
        return Arrays.stream(trimmed.substring(1, trimmed.length() - 1).split(","))
                .map(part -> part.replace("\"", "").trim())
                .filter(part -> !part.isEmpty())
                .map(ChapterId::of)
                .toList();
    }
}
