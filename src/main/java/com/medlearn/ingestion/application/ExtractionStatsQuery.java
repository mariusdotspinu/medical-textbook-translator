package com.medlearn.ingestion.application;

import com.medlearn.ingestion.domain.port.PageLayoutRepository;
import com.medlearn.shared.TextbookId;
import org.springframework.stereotype.Service;

/**
 * Read-side view of what extraction produced.
 *
 * <p>Lives in ingestion rather than in the catalog so that the dependency between the two
 * contexts stays one-way: ingestion writes the catalog, and the catalog knows nothing about
 * page layouts. The API composes both at the controller, which is the right place for it.
 */
@Service
public class ExtractionStatsQuery {

    private final PageLayoutRepository pageLayouts;

    public ExtractionStatsQuery(PageLayoutRepository pageLayouts) {
        this.pageLayouts = pageLayouts;
    }

    /**
     * @param totalCharacters a 1000-page medical textbook reporting only a few thousand
     *                        characters is a scan, not text - worth knowing before spending
     *                        any tokens on it
     */
    public ExtractionStats of(TextbookId textbookId) {
        return new ExtractionStats(
                pageLayouts.countPages(textbookId),
                pageLayouts.totalCharCount(textbookId));
    }

    public record ExtractionStats(int extractedPages, long totalCharacters) {}
}
