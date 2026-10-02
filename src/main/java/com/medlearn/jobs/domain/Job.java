package com.medlearn.jobs.domain;

import com.medlearn.shared.ChapterId;
import com.medlearn.shared.JobId;
import com.medlearn.shared.LanguageTag;
import com.medlearn.shared.TextbookId;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A unit of long-running work the caller polls or streams rather than waits for.
 *
 * <p>Jobs are persisted rather than held in memory so that progress, outcome and findings
 * survive a restart. A job interrupted by a restart is failed rather than resumed; the
 * translation-memory cache makes re-running it cheap.
 *
 * @param findingCount how many {@link JobFinding}s the job has recorded so far; the findings
 *                     themselves are loaded separately, since there can be thousands
 */
public record Job(
        JobId id,
        Optional<TextbookId> textbookId,
        JobType type,
        Optional<LanguageTag> targetLanguage,
        List<ChapterId> chapterIds,
        Map<String, String> options,
        JobState state,
        int findingCount,
        Instant createdAt,
        Optional<Instant> startedAt,
        Optional<Instant> finishedAt) {

    public Job {
        if (id == null) throw new IllegalArgumentException("job id is required");
        if (type == null) throw new IllegalArgumentException("job type is required");
        if (state == null) state = new JobState.Pending();
        if (findingCount < 0) findingCount = 0;
        if (createdAt == null) createdAt = Instant.now();
        textbookId = textbookId == null ? Optional.empty() : textbookId;
        targetLanguage = targetLanguage == null ? Optional.empty() : targetLanguage;
        chapterIds = chapterIds == null ? List.of() : List.copyOf(chapterIds);
        options = options == null ? Map.of() : Map.copyOf(options);
        startedAt = startedAt == null ? Optional.empty() : startedAt;
        finishedAt = finishedAt == null ? Optional.empty() : finishedAt;
    }

    public static Job pending(JobId id, JobSpec spec) {
        return new Job(id, Optional.of(spec.textbookId()), spec.type(), spec.targetLanguage(),
                spec.chapterIds(), spec.options(), new JobState.Pending(), 0, Instant.now(),
                Optional.empty(), Optional.empty());
    }

    public Job withState(JobState newState) {
        return new Job(id, textbookId, type, targetLanguage, chapterIds, options, newState,
                findingCount, createdAt, startedAt, finishedAt);
    }
}
