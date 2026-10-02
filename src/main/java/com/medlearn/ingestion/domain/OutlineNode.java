package com.medlearn.ingestion.domain;

import com.medlearn.shared.PageRange;

import java.util.List;

/**
 * A node of the PDF's bookmark tree, which is the most reliable chapter source in a
 * medical textbook - these books almost always ship a complete outline.
 *
 * @param title    bookmark label as authored
 * @param level    0 for a top-level entry, incrementing with nesting
 * @param range    pages this node covers; the end is derived from the next node in
 *                 document order, since PDF outlines record only a start destination
 * @param children nested sections
 */
public record OutlineNode(String title, int level, PageRange range, List<OutlineNode> children) {

    public OutlineNode {
        if (title == null || title.isBlank()) title = "(untitled)";
        if (level < 0) throw new IllegalArgumentException("level cannot be negative: " + level);
        if (range == null) throw new IllegalArgumentException("outline node needs a page range");
        children = children == null ? List.of() : List.copyOf(children);
    }

    /** This node and all descendants, depth-first - document order. */
    public List<OutlineNode> flatten() {
        return java.util.stream.Stream.concat(
                java.util.stream.Stream.of(this),
                children.stream().flatMap(c -> c.flatten().stream())
        ).toList();
    }

    public OutlineNode withRange(PageRange newRange) {
        return new OutlineNode(title, level, newRange, children);
    }

    public OutlineNode withChildren(List<OutlineNode> newChildren) {
        return new OutlineNode(title, level, range, newChildren);
    }
}
