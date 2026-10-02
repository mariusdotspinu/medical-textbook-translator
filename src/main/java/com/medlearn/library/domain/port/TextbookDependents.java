package com.medlearn.library.domain.port;

import com.medlearn.shared.TextbookId;

/**
 * Something another context keeps for a textbook, which the catalog must clear before the
 * textbook can be deleted.
 *
 * <p>Owned by {@code library} and implemented elsewhere (jobs and their artifacts), so the
 * catalog can delete everything derived from a book without knowing what that is. The
 * dependency points into {@code library}, the same direction as {@code ingestion -> library}.
 */
public interface TextbookDependents {

    /** Whether work for this textbook is still running; deleting under it would race. */
    boolean busy(TextbookId textbookId);

    /**
     * Removes everything derived from the textbook, files included. Called before the
     * textbook row is deleted, while the rows that point at those files still exist.
     */
    void remove(TextbookId textbookId);
}
