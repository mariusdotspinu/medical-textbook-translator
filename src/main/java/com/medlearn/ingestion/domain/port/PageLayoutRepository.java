package com.medlearn.ingestion.domain.port;

import com.medlearn.ingestion.domain.PageLayout;
import com.medlearn.shared.PageRange;
import com.medlearn.shared.TextbookId;

import java.util.List;
import java.util.Optional;

/**
 * Persistence for extracted page layouts.
 *
 * <p>Ingestion owns this model and is the only context that writes it; translation and
 * learning read it. That read coupling is deliberate - {@code PageLayout} is treated as a
 * published language rather than duplicated per context - and is asserted in
 * {@code HexagonalArchitectureTest}.
 */
public interface PageLayoutRepository {

    /** Batched insert. Called once per page group rather than per page. */
    void saveAll(TextbookId textbookId, List<PageLayout> layouts);

    Optional<PageLayout> find(TextbookId textbookId, int pageNo);

    List<PageLayout> findRange(TextbookId textbookId, PageRange range);

    int countPages(TextbookId textbookId);

    /** Total extracted characters, used to sanity-check an ingest and estimate token cost. */
    long totalCharCount(TextbookId textbookId);

    void deleteAll(TextbookId textbookId);
}
