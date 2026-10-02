package com.medlearn.library.domain.port;

import com.medlearn.library.domain.Chapter;
import com.medlearn.shared.ChapterId;
import com.medlearn.shared.TextbookId;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ChapterRepository {

    /**
     * Replaces the textbook's chapter tree.
     *
     * <p>Implementations must insert parents before children - {@code chapter.parent_id} is
     * a self-referencing foreign key, so child-first ordering fails.
     */
    void replaceAll(TextbookId textbookId, List<Chapter> chapters);

    /** Document order. */
    List<Chapter> findByTextbook(TextbookId textbookId);

    Optional<Chapter> findById(ChapterId id);

    List<Chapter> findAllById(Collection<ChapterId> ids);

    void deleteAll(TextbookId textbookId);
}
