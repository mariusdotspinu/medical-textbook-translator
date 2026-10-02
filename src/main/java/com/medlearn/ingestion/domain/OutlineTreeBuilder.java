package com.medlearn.ingestion.domain;

import com.medlearn.shared.PageRange;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Turns chapter entries that only know where they <em>start</em> into a tree of closed
 * page ranges.
 *
 * <p>Shared by both chapter sources that work this way: PDF bookmarks and a printed table
 * of contents. Neither records where an entry ends.
 *
 * <p>An entry ends one page before the next entry that is <b>not its descendant</b>. Using
 * simply "the next entry" is the usual bug: a chapter's first subsection typically starts on
 * the same or the next page, which would collapse every chapter to a page or two while its
 * sections claimed the real content.
 */
public final class OutlineTreeBuilder {

    private OutlineTreeBuilder() {}

    /** An entry with a start page and children, before its end page is known. */
    public record Draft(String title, int level, int startPage, List<Draft> children) {
        public Draft {
            children = children == null ? List.of() : List.copyOf(children);
        }
    }

    /** An entry from a flat, document-ordered list such as a table of contents. */
    public record FlatEntry(String title, int level, int startPage) {}

    /**
     * Nests a flat list by level. A deeper level becomes a child of the nearest shallower
     * entry before it; a jump of several levels at once is treated as one.
     */
    public static List<Draft> nest(List<FlatEntry> entries) {
        record Building(FlatEntry entry, List<Building> children) {
            Draft toDraft(int depth) {
                return new Draft(entry.title(), depth, entry.startPage(),
                        children.stream().map(child -> child.toDraft(depth + 1)).toList());
            }
        }

        List<Building> roots = new ArrayList<>();
        Deque<Building> open = new ArrayDeque<>();
        for (FlatEntry entry : entries) {
            Building node = new Building(entry, new ArrayList<>());
            while (!open.isEmpty() && open.peek().entry().level() >= entry.level()) open.pop();
            if (open.isEmpty()) roots.add(node);
            else open.peek().children().add(node);
            open.push(node);
        }
        return roots.stream().map(root -> root.toDraft(0)).toList();
    }

    /** Closes every entry's range; the last entry of the book runs to {@code pageCount}. */
    public static List<OutlineNode> close(List<Draft> roots, int pageCount) {
        return close(roots, pageCount + 1, pageCount);
    }

    /**
     * @param nextNonDescendantStart start page of the first entry after this whole sibling
     *                               group, or {@code pageCount + 1} at the end of the book
     */
    private static List<OutlineNode> close(
            List<Draft> siblings, int nextNonDescendantStart, int pageCount) {

        List<OutlineNode> closed = new ArrayList<>(siblings.size());
        for (int i = 0; i < siblings.size(); i++) {
            Draft node = siblings.get(i);

            // What follows this node: its next sibling, or whatever follows the parent.
            int boundary = (i + 1 < siblings.size())
                    ? siblings.get(i + 1).startPage()
                    : nextNonDescendantStart;

            List<OutlineNode> children = close(node.children(), boundary, pageCount);

            int end = Math.min(Math.max(node.startPage(), boundary - 1), pageCount);
            closed.add(new OutlineNode(
                    node.title(),
                    node.level(),
                    PageRange.of(Math.min(node.startPage(), pageCount), end),
                    children));
        }
        return List.copyOf(closed);
    }
}
