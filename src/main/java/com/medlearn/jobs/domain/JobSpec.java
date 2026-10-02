package com.medlearn.jobs.domain;

import com.medlearn.shared.ChapterId;
import com.medlearn.shared.LanguageTag;
import com.medlearn.shared.TextbookId;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What a job is about, recorded when it is submitted so the job describes itself later.
 *
 * @param options request settings worth showing next to the job, e.g. {@code renderMode} or
 *                {@code dryRun}. Plain strings, since {@code jobs} knows nothing of the
 *                contexts that define them.
 */
public record JobSpec(
        JobType type,
        TextbookId textbookId,
        Optional<LanguageTag> targetLanguage,
        List<ChapterId> chapterIds,
        Map<String, String> options) {

    public JobSpec {
        if (type == null) throw new IllegalArgumentException("job type is required");
        if (textbookId == null) throw new IllegalArgumentException("textbook id is required");
        targetLanguage = targetLanguage == null ? Optional.empty() : targetLanguage;
        chapterIds = chapterIds == null ? List.of() : List.copyOf(chapterIds);
        options = options == null ? Map.of() : Map.copyOf(options);
    }

    /** A job about a whole textbook, with no language or chapter selection. */
    public static JobSpec of(JobType type, TextbookId textbookId) {
        return new JobSpec(type, textbookId, Optional.empty(), List.of(), Map.of());
    }
}
