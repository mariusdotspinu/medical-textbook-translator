package com.medlearn.ingestion.application;

import com.medlearn.ingestion.domain.OutlineNode;
import com.medlearn.library.domain.Chapter;
import com.medlearn.library.domain.ChapterSource;
import com.medlearn.shared.ChapterId;
import com.medlearn.shared.PageRange;
import com.medlearn.shared.TextbookId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Turns whatever structure a PDF offers into a chapter tree.
 *
 * <p>The bookmark tree is used when present, which for medical textbooks is nearly always.
 * Ordinals are assigned in document order across the whole book - not within each parent -
 * so "translate chapters 1 through 8" is a range over one column rather than a tree walk.
 *
 * <p>Sources, in order of preference: the bookmark tree ({@link ChapterSource#OUTLINE}), a
 * printed table of contents ({@link ChapterSource#TOC}, found by
 * {@link TableOfContentsDetector} after the pages are extracted), and otherwise a single
 * "Full text" span ({@link ChapterSource#NONE}). Font-size heuristics
 * ({@link ChapterSource#HEURISTIC}) are not implemented.
 */
@Service
public class ChapterDetectionService {

    private static final Logger log = LoggerFactory.getLogger(ChapterDetectionService.class);

    /**
     * Some documents carry an outline entry per paragraph. Past this many entries the
     * deepest levels are pruned - a 20,000-row chapter list is unusable in a UI and is not
     * what a reader means by "chapter".
     */
    private static final int MAX_CHAPTERS = 4000;

    public DetectedChapters detect(TextbookId textbookId, List<OutlineNode> outline, int pageCount) {
        return detect(textbookId, outline, pageCount, ChapterSource.OUTLINE);
    }

    /**
     * @param source where {@code outline} came from: the PDF's bookmarks, or a printed table
     *               of contents turned into the same tree shape
     */
    public DetectedChapters detect(
            TextbookId textbookId, List<OutlineNode> outline, int pageCount, ChapterSource source) {
        if (outline.isEmpty()) {
            log.info("No outline for {}; treating the book as a single span", textbookId);
            return new DetectedChapters(
                    List.of(new Chapter(
                            ChapterId.newId(), textbookId, Optional.empty(), 0, 0,
                            "Full text", PageRange.of(1, Math.max(1, pageCount)))),
                    ChapterSource.NONE);
        }

        List<OutlineNode> pruned = pruneIfExcessive(outline);

        List<Chapter> chapters = new ArrayList<>();
        AtomicInteger ordinal = new AtomicInteger();
        for (OutlineNode root : pruned) {
            flatten(root, Optional.empty(), textbookId, ordinal, chapters);
        }

        log.info("Detected {} chapter nodes for {} from the {}", chapters.size(), textbookId,
                source == ChapterSource.TOC ? "printed table of contents" : "PDF outline");
        return new DetectedChapters(List.copyOf(chapters), source);
    }

    private void flatten(
            OutlineNode node,
            Optional<ChapterId> parentId,
            TextbookId textbookId,
            AtomicInteger ordinal,
            List<Chapter> sink) {

        ChapterId id = ChapterId.newId();
        sink.add(new Chapter(
                id, textbookId, parentId, ordinal.getAndIncrement(),
                node.level(), node.title(), node.range()));

        for (OutlineNode child : node.children()) {
            flatten(child, Optional.of(id), textbookId, ordinal, sink);
        }
    }

    /** Drops the deepest level repeatedly until the tree is a sane size. */
    private List<OutlineNode> pruneIfExcessive(List<OutlineNode> roots) {
        List<OutlineNode> current = roots;
        int total = countNodes(current);
        int maxLevel = maxLevel(current);

        while (total > MAX_CHAPTERS && maxLevel > 0) {
            log.info("Outline has {} entries; pruning level {}", total, maxLevel);
            current = removeLevel(current, maxLevel);
            total = countNodes(current);
            maxLevel--;
        }
        return current;
    }

    /** Drops every node at or below {@code levelToRemove}, keeping shallower ancestors. */
    private static List<OutlineNode> removeLevel(List<OutlineNode> nodes, int levelToRemove) {
        return nodes.stream()
                .filter(node -> node.level() < levelToRemove)
                .map(node -> node.withChildren(removeLevel(node.children(), levelToRemove)))
                .toList();
    }

    private static int countNodes(List<OutlineNode> nodes) {
        return nodes.stream().mapToInt(node -> node.flatten().size()).sum();
    }

    private static int maxLevel(List<OutlineNode> nodes) {
        return nodes.stream()
                .flatMap(node -> node.flatten().stream())
                .mapToInt(OutlineNode::level)
                .max()
                .orElse(0);
    }

    /**
     * @param source how the chapters were found, surfaced to the reader so they know how
     *               much to trust the list
     */
    public record DetectedChapters(List<Chapter> chapters, ChapterSource source) {}
}
