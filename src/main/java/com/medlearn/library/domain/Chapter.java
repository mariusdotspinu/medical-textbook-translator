package com.medlearn.library.domain;

import com.medlearn.shared.ChapterId;
import com.medlearn.shared.PageRange;
import com.medlearn.shared.TextbookId;

import java.util.Optional;

/**
 * A node of a textbook's chapter tree - a part, chapter, or section.
 *
 * <p>Sections are kept rather than flattened to top-level chapters because a reader may
 * reasonably want to translate one section of a 90-page chapter instead of all of it.
 *
 * @param ordinal position in document order across the whole book, not within the parent;
 *                this is what makes "chapters 1 through 8" a simple range query
 * @param level   0 for top-level entries, incrementing with nesting
 */
public record Chapter(
        ChapterId id,
        TextbookId textbookId,
        Optional<ChapterId> parentId,
        int ordinal,
        int level,
        String title,
        PageRange range) {

    public Chapter {
        if (id == null) throw new IllegalArgumentException("chapter id is required");
        if (textbookId == null) throw new IllegalArgumentException("textbook id is required");
        if (title == null || title.isBlank()) title = "(untitled)";
        if (range == null) throw new IllegalArgumentException("chapter page range is required");
        if (ordinal < 0) throw new IllegalArgumentException("ordinal cannot be negative");
        if (level < 0) throw new IllegalArgumentException("level cannot be negative");
        parentId = parentId == null ? Optional.empty() : parentId;
    }

    public boolean topLevel() {
        return level == 0;
    }

    public int pageCount() {
        return range.pageCount();
    }
}
