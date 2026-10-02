package com.medlearn.library.application;

import com.medlearn.library.domain.Chapter;
import com.medlearn.library.domain.Textbook;
import com.medlearn.library.domain.port.ChapterRepository;
import com.medlearn.library.domain.port.TextbookDependents;
import com.medlearn.library.domain.port.TextbookRepository;
import com.medlearn.shared.ChapterId;
import com.medlearn.shared.DomainError;
import com.medlearn.shared.PageRange;
import com.medlearn.shared.TextbookId;
import com.medlearn.shared.port.BlobStorePort;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Read-side queries over the catalog. */
@Service
public class TextbookCatalogService {

    private final TextbookRepository textbooks;
    private final ChapterRepository chapters;
    private final BlobStorePort blobStore;
    private final List<TextbookDependents> dependents;

    public TextbookCatalogService(
            TextbookRepository textbooks,
            ChapterRepository chapters,
            BlobStorePort blobStore,
            List<TextbookDependents> dependents) {
        this.textbooks = textbooks;
        this.chapters = chapters;
        this.blobStore = blobStore;
        this.dependents = List.copyOf(dependents);
    }

    public List<Textbook> findAll() {
        return textbooks.findAll();
    }

    public Optional<Textbook> find(TextbookId id) {
        return textbooks.findById(id);
    }

    /** Flat list in document order. */
    public List<Chapter> flatChapters(TextbookId id) {
        return chapters.findByTextbook(id);
    }

    /** Nested tree for display, preserving document order at every level. */
    public List<ChapterNode> chapterTree(TextbookId id) {
        List<Chapter> flat = chapters.findByTextbook(id);

        Map<ChapterId, List<Chapter>> childrenByParent = new LinkedHashMap<>();
        List<Chapter> roots = new ArrayList<>();
        for (Chapter chapter : flat) {
            chapter.parentId().ifPresentOrElse(
                    parent -> childrenByParent.computeIfAbsent(parent, key -> new ArrayList<>()).add(chapter),
                    () -> roots.add(chapter));
        }
        return roots.stream().map(root -> toNode(root, childrenByParent)).toList();
    }

    private ChapterNode toNode(Chapter chapter, Map<ChapterId, List<Chapter>> childrenByParent) {
        List<ChapterNode> children = childrenByParent.getOrDefault(chapter.id(), List.of())
                .stream()
                .map(child -> toNode(child, childrenByParent))
                .toList();
        return new ChapterNode(chapter, children);
    }

    /** Smallest page span covering the given chapters, for a bulk translation request. */
    public Optional<PageRange> spanOf(List<ChapterId> chapterIds) {
        return chapters.findAllById(chapterIds).stream()
                .map(Chapter::range)
                .reduce(PageRange::union);
    }

    /**
     * Removes the textbook, everything other contexts derived from it (jobs, artifact files),
     * its cascaded rows, and the stored original.
     *
     * <p>Refused while any job for the book is running: a translation finishing after the
     * cleanup would write a new artifact file that nothing references.
     */
    public DeleteOutcome delete(TextbookId id) {
        Optional<Textbook> textbook = textbooks.findById(id);
        if (textbook.isEmpty()) {
            return new DeleteOutcome.Rejected(new DomainError.TextbookNotFound(id));
        }
        if (dependents.stream().anyMatch(dependent -> dependent.busy(id))) {
            return new DeleteOutcome.Rejected(new DomainError.TextbookBusy(id));
        }

        dependents.forEach(dependent -> dependent.remove(id));
        textbooks.delete(id);
        // Delete the blob only after the row is gone, so a failure here leaves an orphaned
        // file rather than a database row pointing at nothing.
        blobStore.delete(textbook.get().blob());
        return new DeleteOutcome.Deleted();
    }

    public record ChapterNode(Chapter chapter, List<ChapterNode> children) {}

    public sealed interface DeleteOutcome {
        record Deleted() implements DeleteOutcome {}

        record Rejected(DomainError error) implements DeleteOutcome {}
    }
}
