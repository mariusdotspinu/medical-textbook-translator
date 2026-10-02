package com.medlearn.ingestion.application;

import com.medlearn.ingestion.domain.OutlineNode;
import com.medlearn.library.domain.Chapter;
import com.medlearn.library.domain.ChapterSource;
import com.medlearn.shared.PageRange;
import com.medlearn.shared.TextbookId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChapterDetectionServiceTest {

    private final ChapterDetectionService service = new ChapterDetectionService();
    private final TextbookId textbookId = TextbookId.newId();

    private static OutlineNode node(String title, int level, int start, int end, OutlineNode... children) {
        return new OutlineNode(title, level, PageRange.of(start, end), List.of(children));
    }

    @Test
    @DisplayName("a bookmark tree becomes chapters in document order with parents wired up")
    void flattensOutlineTree() {
        List<OutlineNode> outline = List.of(
                node("Chapter 1", 0, 1, 40,
                        node("1.1 Anatomy", 1, 1, 20),
                        node("1.2 Physiology", 1, 21, 40)),
                node("Chapter 2", 0, 41, 90));

        var detected = service.detect(textbookId, outline, 90);

        assertThat(detected.source()).isEqualTo(ChapterSource.OUTLINE);
        assertThat(detected.chapters()).hasSize(4);

        // Ordinals run across the whole book, not per parent - that is what makes
        // "chapters 1 through 8" a single range query.
        assertThat(detected.chapters().stream().map(Chapter::title))
                .containsExactly("Chapter 1", "1.1 Anatomy", "1.2 Physiology", "Chapter 2");
        assertThat(detected.chapters().stream().map(Chapter::ordinal))
                .containsExactly(0, 1, 2, 3);

        Chapter chapterOne = detected.chapters().getFirst();
        assertThat(chapterOne.parentId()).isEmpty();
        assertThat(chapterOne.topLevel()).isTrue();

        Chapter subsection = detected.chapters().get(1);
        assertThat(subsection.parentId()).contains(chapterOne.id());
        assertThat(subsection.level()).isEqualTo(1);
    }

    @Test
    @DisplayName("a book with no outline becomes a single translatable span")
    void fallsBackToOneSpanWithoutAnOutline() {
        var detected = service.detect(textbookId, List.of(), 742);

        assertThat(detected.source()).isEqualTo(ChapterSource.NONE);
        assertThat(detected.chapters()).singleElement()
                .satisfies(chapter -> {
                    assertThat(chapter.title()).isEqualTo("Full text");
                    assertThat(chapter.range()).isEqualTo(PageRange.of(1, 742));
                });
    }

    @Test
    @DisplayName("an outline with an entry per paragraph is pruned to something usable")
    void prunesPathologicallyDeepOutlines() {
        // Some documents bookmark every paragraph. 4500 leaves under 3 roots exceeds the
        // cap, so the deepest level is dropped rather than producing an unusable list.
        List<OutlineNode> deep = List.of(
                rootWithChildren("Part I", 1500),
                rootWithChildren("Part II", 1500),
                rootWithChildren("Part III", 1500));

        var detected = service.detect(textbookId, deep, 3000);

        assertThat(detected.chapters()).hasSizeLessThanOrEqualTo(4000);
        assertThat(detected.chapters().stream().map(Chapter::title))
                .contains("Part I", "Part II", "Part III");
    }

    private static OutlineNode rootWithChildren(String title, int childCount) {
        OutlineNode[] children = new OutlineNode[childCount];
        for (int i = 0; i < childCount; i++) {
            children[i] = node("leaf " + i, 1, i + 1, i + 1);
        }
        return node(title, 0, 1, childCount, children);
    }
}
