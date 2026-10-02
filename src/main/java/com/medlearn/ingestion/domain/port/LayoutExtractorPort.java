package com.medlearn.ingestion.domain.port;

import com.medlearn.ingestion.domain.PageLayout;
import com.medlearn.ingestion.domain.TextBlock;

import java.nio.file.Path;
import java.util.List;

/**
 * Extracts page geometry and positioned text from a PDF.
 *
 * <p>Split into a cheap {@link #probe} and a {@link Session} because the two have very
 * different costs: probing reads the catalog, while a session parses content streams.
 *
 * <p><b>Concurrency contract:</b> each {@link Session} owns an independent document
 * handle, so sessions may be used in parallel - but a single session must be confined to
 * one thread. This mirrors PDFBox, where {@code PDDocument} is not thread-safe; the
 * pipeline therefore opens one session per worker rather than sharing one.
 */
public interface LayoutExtractorPort {

    /** Reads the document catalog only: enough to create the textbook row immediately. */
    DocumentProbe probe(Path pdfFile);

    /** Opens an independent, single-threaded extraction session. */
    Session open(Path pdfFile);

    /**
     * @param title   embedded document title, or {@code null}
     * @param authors embedded author list, or {@code null}
     */
    record DocumentProbe(int pageCount, String title, String authors, boolean encrypted) {}

    interface Session extends AutoCloseable {

        /** @param pageNo 1-based */
        PageLayout extractPage(int pageNo);

        /**
         * One block per visual line, column-aware, without paragraph merging. For reading
         * line-structured pages such as a table of contents.
         */
        List<TextBlock> extractLines(int pageNo);

        int pageCount();

        @Override
        void close();
    }
}
