package com.medlearn.translation.adapter.web;

import com.medlearn.shared.ChapterId;
import com.medlearn.shared.LanguageTag;
import com.medlearn.shared.TextbookId;
import com.medlearn.translation.application.TranslateChaptersUseCase;
import com.medlearn.translation.application.TranslateChaptersUseCase.Command;
import com.medlearn.translation.application.TranslateChaptersUseCase.Outcome;
import com.medlearn.translation.domain.RenderMode;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/textbooks/{id}/translations")
@Validated
public class TranslationController {

    private final TranslateChaptersUseCase translate;

    public TranslationController(TranslateChaptersUseCase translate) {
        this.translate = translate;
    }

    /**
     * Starts a translation and returns a job to watch.
     *
     * <p>A chapter <em>range</em> produces one PDF, not one per chapter.
     */
    @PostMapping
    public ResponseEntity<?> start(@PathVariable String id, @RequestBody TranslateRequest request) {
        Command command = new Command(
                TextbookId.of(id),
                request.chapterIds() == null
                        ? List.of()
                        : request.chapterIds().stream().map(ChapterId::of).toList(),
                request.fromChapter() == null ? 0 : request.fromChapter(),
                request.toChapter() == null ? Integer.MAX_VALUE : request.toChapter(),
                LanguageTag.of(request.targetLanguage()),
                request.renderMode() == null ? RenderMode.OVERLAY : request.renderMode(),
                Boolean.TRUE.equals(request.includeStudyGuide()),
                Boolean.TRUE.equals(request.dryRun()));

        return switch (translate.submit(command)) {
            case Outcome.Accepted accepted -> ResponseEntity.accepted().body(new Started(
                    accepted.jobId().toString(),
                    command.dryRun()
                            ? "Dry run: the pipeline will run with a free, non-calling translator."
                            : "Translation started. Watch the job for progress."));
            case Outcome.Rejected rejected ->
                    ResponseEntity.unprocessableEntity().body(new Error(rejected.error().message()));
        };
    }

    /**
     * @param chapterIds explicit selection; when absent, {@code fromChapter}/{@code toChapter}
     *                   select by chapter ordinal instead
     * @param dryRun     runs everything except the paid API call - the way to check render
     *                   fidelity for free
     */
    public record TranslateRequest(
            List<String> chapterIds,
            Integer fromChapter,
            Integer toChapter,
            @NotBlank String targetLanguage,
            RenderMode renderMode,
            Boolean includeStudyGuide,
            Boolean dryRun) {}

    record Started(String jobId, String message) {}

    record Error(String error) {}
}
