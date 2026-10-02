package com.medlearn.ingestion.domain.port;

import com.medlearn.ingestion.domain.PageLayout;

import java.nio.file.Path;
import java.util.List;

/**
 * Renders extraction results visually, over the original pages.
 *
 * <p>Exists because extraction defects are geometric and nearly invisible in text form: a
 * reading order that silently interleaves two columns looks like ordinary prose until you
 * see the boxes and their numbering.
 */
public interface DebugRendererPort {

    /**
     * @param pdfFile the original document, drawn underneath so boxes can be compared with
     *                what is actually on the page
     * @param layouts pages to render, in order
     * @return a PDF
     */
    byte[] renderLayoutOverlay(Path pdfFile, List<PageLayout> layouts);
}
