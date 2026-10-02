package com.medlearn.translation.adapter.web;

import com.medlearn.shared.LanguageTag;
import com.medlearn.shared.TextbookId;
import com.medlearn.translation.application.GlossaryService;
import com.medlearn.translation.domain.GlossaryTerm;
import com.medlearn.translation.domain.GlossaryTerm.TermKind;
import com.medlearn.translation.domain.GlossaryTermId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A book's glossary for one target language: what was extracted, and readers' corrections.
 *
 * <p>{@code PUT} is an upsert by source term and always pins: a term a reader has touched is
 * never changed by extraction again.
 */
@RestController
@RequestMapping("/api/v1/textbooks/{id}/glossary/{lang}")
@Validated
public class GlossaryController {

    private final GlossaryService glossary;

    public GlossaryController(GlossaryService glossary) {
        this.glossary = glossary;
    }

    @GetMapping
    public ResponseEntity<?> list(@PathVariable String id, @PathVariable String lang) {
        return glossary.list(TextbookId.of(id), LanguageTag.of(lang))
                .<ResponseEntity<?>>map(terms -> ResponseEntity.ok(
                        terms.stream().map(TermResponse::from).toList()))
                .orElseGet(() -> notFound(id));
    }

    @PutMapping
    public ResponseEntity<?> save(@PathVariable String id, @PathVariable String lang,
                                  @Valid @RequestBody TermRequest request) {
        return glossary.save(TextbookId.of(id), LanguageTag.of(lang),
                        request.sourceTerm(), request.targetTerm(), request.kind())
                .<ResponseEntity<?>>map(term -> ResponseEntity.ok(TermResponse.from(term)))
                .orElseGet(() -> notFound(id));
    }

    @DeleteMapping("/{termId}")
    public ResponseEntity<?> delete(@PathVariable String id, @PathVariable String lang,
                                    @PathVariable String termId) {
        boolean deleted = glossary.delete(
                TextbookId.of(id), LanguageTag.of(lang), GlossaryTermId.of(termId));
        return deleted
                ? ResponseEntity.noContent().build()
                : ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(new ErrorResponse("No such glossary term"));
    }

    private static ResponseEntity<?> notFound(String id) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("No textbook with id " + id));
    }

    /** @param kind optional; an existing term keeps its kind when omitted */
    public record TermRequest(
            @NotBlank @Size(max = 80) String sourceTerm,
            @NotBlank @Size(max = 80) String targetTerm,
            TermKind kind) {}

    record TermResponse(
            String id, String sourceTerm, String targetTerm, String kind, int occurrences,
            boolean pinned) {

        static TermResponse from(GlossaryTerm term) {
            return new TermResponse(term.id().toString(), term.sourceTerm(), term.targetTerm(),
                    term.kind().name(), term.occurrences(), term.pinned());
        }
    }

    record ErrorResponse(String error) {}
}
