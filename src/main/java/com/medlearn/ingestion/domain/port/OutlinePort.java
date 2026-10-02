package com.medlearn.ingestion.domain.port;

import com.medlearn.ingestion.domain.OutlineNode;

import java.nio.file.Path;
import java.util.List;

/**
 * Reads the PDF bookmark tree, the primary chapter source.
 *
 * @see com.medlearn.ingestion.application.ChapterDetectionService for the fallbacks used
 *      when a document ships no outline
 */
public interface OutlinePort {

    /**
     * @param pageCount total pages, needed to close the last node's range - PDF outlines
     *                  record only a start destination per entry
     * @return top-level nodes in document order, empty if the document has no outline
     */
    List<OutlineNode> readOutline(Path pdfFile, int pageCount);
}
