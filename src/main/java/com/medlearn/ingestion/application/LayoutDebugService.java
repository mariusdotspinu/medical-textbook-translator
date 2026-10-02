package com.medlearn.ingestion.application;

import com.medlearn.ingestion.domain.PageLayout;
import com.medlearn.ingestion.domain.port.DebugRendererPort;
import com.medlearn.ingestion.domain.port.PageLayoutRepository;
import com.medlearn.library.domain.Textbook;
import com.medlearn.library.domain.port.TextbookRepository;
import com.medlearn.shared.PageRange;
import com.medlearn.shared.TextbookId;
import com.medlearn.shared.port.BlobStorePort;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Produces the layout-debug PDF for a page range.
 *
 * <p>Capped at a modest number of pages per request: this is a diagnostic you look at, and
 * rendering 1000 annotated pages would be slow to produce and useless to read.
 */
@Service
public class LayoutDebugService {

    private static final int MAX_PAGES_PER_REQUEST = 20;

    private final TextbookRepository textbooks;
    private final PageLayoutRepository pageLayouts;
    private final BlobStorePort blobStore;
    private final DebugRendererPort renderer;

    public LayoutDebugService(
            TextbookRepository textbooks,
            PageLayoutRepository pageLayouts,
            BlobStorePort blobStore,
            DebugRendererPort renderer) {
        this.textbooks = textbooks;
        this.pageLayouts = pageLayouts;
        this.blobStore = blobStore;
        this.renderer = renderer;
    }

    /** @return the rendered PDF, or empty if the textbook or the requested pages are unknown */
    public Optional<byte[]> render(TextbookId textbookId, int fromPage, int toPage) {
        Optional<Textbook> textbook = textbooks.findById(textbookId);
        if (textbook.isEmpty()) return Optional.empty();

        int from = Math.max(1, fromPage);
        int to = Math.min(Math.max(from, toPage), from + MAX_PAGES_PER_REQUEST - 1);

        List<PageLayout> layouts = pageLayouts.findRange(textbookId, PageRange.of(from, to));
        if (layouts.isEmpty()) return Optional.empty();

        return Optional.of(renderer.renderLayoutOverlay(
                blobStore.resolveToLocalFile(textbook.get().blob()), layouts));
    }

    public int maxPagesPerRequest() {
        return MAX_PAGES_PER_REQUEST;
    }
}
