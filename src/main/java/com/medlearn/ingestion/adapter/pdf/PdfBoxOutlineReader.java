package com.medlearn.ingestion.adapter.pdf;

import com.medlearn.ingestion.domain.OutlineNode;
import com.medlearn.ingestion.domain.OutlineTreeBuilder;
import com.medlearn.ingestion.domain.port.OutlinePort;
import com.medlearn.shared.PageRange;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.io.RandomAccessReadBufferedFile;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the PDF bookmark tree into {@link OutlineNode}s with closed page ranges.
 *
 * <p>A PDF outline entry records only where it <em>starts</em>; nothing says where it ends.
 * Ranges are closed by {@link OutlineTreeBuilder}, shared with table-of-contents detection,
 * which explains why "the next entry" is the wrong end.
 */
@Component
public class PdfBoxOutlineReader implements OutlinePort {

    private static final Logger log = LoggerFactory.getLogger(PdfBoxOutlineReader.class);

    /** Guards against pathological or circular outlines in damaged files. */
    private static final int MAX_DEPTH = 12;

    @Override
    public List<OutlineNode> readOutline(Path pdfFile, int pageCount) {
        try (PDDocument document = Loader.loadPDF(
                new RandomAccessReadBufferedFile(pdfFile.toFile()),
                IOUtils.createTempFileOnlyStreamCache())) {

            PDDocumentOutline outline = document.getDocumentCatalog().getDocumentOutline();
            if (outline == null) {
                log.info("{} has no bookmark tree; chapter detection will fall back", pdfFile);
                return List.of();
            }

            List<OutlineTreeBuilder.Draft> roots = readChildren(outline, document, 0);
            if (roots.isEmpty()) return List.of();

            return OutlineTreeBuilder.close(roots, pageCount);
        } catch (IOException e) {
            throw new PdfAccessException("Could not read outline of " + pdfFile, e);
        }
    }

    private List<OutlineTreeBuilder.Draft> readChildren(
            PDOutlineNode parent, PDDocument document, int level) {
        if (level >= MAX_DEPTH) return List.of();

        List<OutlineTreeBuilder.Draft> result = new ArrayList<>();
        for (PDOutlineItem item : parent.children()) {
            Integer startPage = resolveStartPage(item, document);
            if (startPage == null) continue;   // unresolvable destination: skip, keep the rest
            result.add(new OutlineTreeBuilder.Draft(
                    titleOf(item),
                    level,
                    startPage,
                    readChildren(item, document, level + 1)));
        }
        return result;
    }

    private static Integer resolveStartPage(PDOutlineItem item, PDDocument document) {
        try {
            PDPage page = item.findDestinationPage(document);
            if (page == null) return null;
            int index = document.getPages().indexOf(page);
            return index < 0 ? null : index + 1;   // to 1-based
        } catch (IOException | RuntimeException e) {
            log.debug("Unresolvable outline destination '{}': {}", titleOf(item), e.getMessage());
            return null;
        }
    }

    private static String titleOf(PDOutlineItem item) {
        try {
            String title = item.getTitle();
            return title == null || title.isBlank() ? "(untitled)" : title.trim();
        } catch (RuntimeException e) {
            return "(untitled)";
        }
    }

}
