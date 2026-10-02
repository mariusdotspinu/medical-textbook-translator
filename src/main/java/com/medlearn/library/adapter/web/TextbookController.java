package com.medlearn.library.adapter.web;

import com.medlearn.library.application.TextbookCatalogService;
import com.medlearn.library.application.TextbookCatalogService.ChapterNode;
import com.medlearn.library.application.TextbookCatalogService.DeleteOutcome;
import com.medlearn.library.domain.Chapter;
import com.medlearn.library.domain.Textbook;
import com.medlearn.shared.DomainError;
import com.medlearn.shared.LanguageTag;
import com.medlearn.shared.TextbookId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/textbooks")
public class TextbookController {

    private static final Logger log = LoggerFactory.getLogger(TextbookController.class);

    private final TextbookCatalogService catalog;

    public TextbookController(TextbookCatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    public List<TextbookSummary> list() {
        return catalog.findAll().stream().map(TextbookSummary::from).toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> detail(@PathVariable String id) {
        TextbookId textbookId = TextbookId.of(id);
        return catalog.find(textbookId)
                .<ResponseEntity<?>>map(textbook -> ResponseEntity.ok(TextbookDetail.from(textbook)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(new ErrorResponse("No textbook with id " + id)));
    }

    @GetMapping("/{id}/chapters")
    public ResponseEntity<?> chapters(@PathVariable String id) {
        TextbookId textbookId = TextbookId.of(id);
        if (catalog.find(textbookId).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ErrorResponse("No textbook with id " + id));
        }
        return ResponseEntity.ok(catalog.chapterTree(textbookId).stream()
                .map(ChapterResponse::from)
                .toList());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        TextbookId textbookId = TextbookId.of(id);
        return switch (catalog.delete(textbookId)) {
            case DeleteOutcome.Deleted ignored -> {
                log.info("Deleted textbook {}", textbookId);
                yield ResponseEntity.noContent().build();
            }
            case DeleteOutcome.Rejected(DomainError.TextbookNotFound notFound) ->
                    ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(notFound.message()));
            case DeleteOutcome.Rejected(DomainError.TextbookBusy busy) ->
                    ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse(busy.message()));
            case DeleteOutcome.Rejected rejected ->
                    ResponseEntity.unprocessableEntity().body(new ErrorResponse(rejected.error().message()));
        };
    }

    // --- response shapes ---------------------------------------------------

    record ErrorResponse(String error) {}

    record TextbookSummary(
            String id, String title, String authors, int pageCount, long byteSize,
            String status, String chapterSource, String uploadedAt) {

        static TextbookSummary from(Textbook textbook) {
            return new TextbookSummary(
                    textbook.id().toString(),
                    textbook.title(),
                    textbook.authors().orElse(null),
                    textbook.pageCount(),
                    textbook.byteSize(),
                    textbook.status().name(),
                    textbook.chapterSource().name(),
                    textbook.uploadedAt().toString());
        }
    }

    /** Extraction counts live under {@code GET /{id}/extraction}, served by ingestion. */
    record TextbookDetail(
            String id, String title, String authors, String sourceLanguage, int pageCount,
            long byteSize, String status, String chapterSource, String uploadedAt,
            String ingestedAt) {

        static TextbookDetail from(Textbook textbook) {
            return new TextbookDetail(
                    textbook.id().toString(),
                    textbook.title(),
                    textbook.authors().orElse(null),
                    textbook.sourceLanguage().map(LanguageTag::tag).orElse(null),
                    textbook.pageCount(),
                    textbook.byteSize(),
                    textbook.status().name(),
                    textbook.chapterSource().name(),
                    textbook.uploadedAt().toString(),
                    textbook.ingestedAt().map(Object::toString).orElse(null));
        }
    }

    record ChapterResponse(
            String id, int ordinal, int level, String title,
            int startPage, int endPage, int pageCount, List<ChapterResponse> children) {

        static ChapterResponse from(ChapterNode node) {
            Chapter chapter = node.chapter();
            return new ChapterResponse(
                    chapter.id().toString(),
                    chapter.ordinal(),
                    chapter.level(),
                    chapter.title(),
                    chapter.range().startPage(),
                    chapter.range().endPage(),
                    chapter.pageCount(),
                    node.children().stream().map(ChapterResponse::from).toList());
        }
    }
}
