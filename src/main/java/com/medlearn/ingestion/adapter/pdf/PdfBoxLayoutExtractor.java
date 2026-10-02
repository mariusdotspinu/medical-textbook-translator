package com.medlearn.ingestion.adapter.pdf;

import com.medlearn.ingestion.domain.PageSegmenter;
import com.medlearn.ingestion.domain.PageKind;
import com.medlearn.ingestion.domain.PageLayout;
import com.medlearn.ingestion.domain.TextBlock;
import com.medlearn.ingestion.domain.TextRun;
import com.medlearn.ingestion.domain.port.LayoutExtractorPort;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.io.RandomAccessReadBufferedFile;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Path;
import java.util.List;

/**
 * PDFBox-backed extraction.
 *
 * <p>Two things here are load-bearing for a 500MB, 1000-page textbook:
 *
 * <ol>
 *   <li>Documents are opened with a <b>temp-file stream cache</b>, so PDFBox spools object
 *       streams to disk instead of the heap. Without it, a large textbook exhausts memory
 *       before the first page is read.
 *   <li>Each {@link Session} owns its own {@code PDDocument}, because {@code PDDocument} is
 *       not thread-safe. The pipeline opens one session per worker on the same file rather
 *       than sharing one across threads.
 * </ol>
 */
@Component
public class PdfBoxLayoutExtractor implements LayoutExtractorPort {

    private static final Logger log = LoggerFactory.getLogger(PdfBoxLayoutExtractor.class);

    /** Below this many characters a page is suspected of being a scan rather than text. */
    private static final int SCANNED_CHAR_THRESHOLD = 20;

    /** Pixel dimensions above which an image is big enough to be a whole scanned page. */
    private static final int LARGE_IMAGE_MIN_PIXELS = 500;

    @Override
    public DocumentProbe probe(Path pdfFile) {
        try (PDDocument document = openDocument(pdfFile)) {
            PDDocumentInformation info = document.getDocumentInformation();
            return new DocumentProbe(
                    document.getNumberOfPages(),
                    blankToNull(info == null ? null : info.getTitle()),
                    blankToNull(info == null ? null : info.getAuthor()),
                    false);
        } catch (InvalidPasswordException e) {
            // Owner-password-only documents open fine; this means a user password we lack.
            return new DocumentProbe(0, null, null, true);
        } catch (IOException e) {
            throw new PdfAccessException("Could not read PDF " + pdfFile, e);
        }
    }

    @Override
    public Session open(Path pdfFile) {
        return new PdfBoxSession(pdfFile);
    }

    private static PDDocument openDocument(Path pdfFile) throws IOException {
        return Loader.loadPDF(
                new RandomAccessReadBufferedFile(pdfFile.toFile()),
                IOUtils.createTempFileOnlyStreamCache());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** One document handle, confined to one thread. */
    private static final class PdfBoxSession implements Session {

        private final Path file;
        private final PDDocument document;
        private final PositionalTextStripper stripper;

        private PdfBoxSession(Path file) {
            this.file = file;
            try {
                this.document = openDocument(file);
                this.stripper = new PositionalTextStripper();
            } catch (IOException e) {
                throw new PdfAccessException("Could not open PDF " + file, e);
            }
        }

        @Override
        public int pageCount() {
            return document.getNumberOfPages();
        }

        @Override
        public PageLayout extractPage(int pageNo) {
            if (pageNo < 1 || pageNo > pageCount()) {
                throw new IllegalArgumentException(
                        "page %d out of range 1..%d".formatted(pageNo, pageCount()));
            }

            PDPage page = document.getPage(pageNo - 1);
            PageGeometry geometry = geometryOf(page);

            List<TextRun> runs = captureRuns(pageNo);
            // Columns first, then lines, then paragraphs - see PageSegmenter for why the
            // order cannot be rearranged.
            List<TextBlock> blocks = PageSegmenter.segment(pageNo, runs, geometry.width());
            int charCount = blocks.stream().mapToInt(TextBlock::charCount).sum();

            PageKind kind = classify(page, charCount);
            return new PageLayout(pageNo, geometry.width(), geometry.height(), kind, blocks);
        }

        @Override
        public List<TextBlock> extractLines(int pageNo) {
            if (pageNo < 1 || pageNo > pageCount()) {
                throw new IllegalArgumentException(
                        "page %d out of range 1..%d".formatted(pageNo, pageCount()));
            }
            PageGeometry geometry = geometryOf(document.getPage(pageNo - 1));
            return PageSegmenter.segmentLines(pageNo, captureRuns(pageNo), geometry.width());
        }

        private List<TextRun> captureRuns(int pageNo) {
            stripper.reset();
            stripper.setStartPage(pageNo);
            stripper.setEndPage(pageNo);
            try {
                // nullWriter: the assembled string is discarded - the runs captured in
                // writeString are the actual output. Over 1000 pages this avoids building
                // and throwing away a full text copy of the book.
                stripper.writeText(document, Writer.nullWriter());
            } catch (IOException e) {
                throw new PdfAccessException(
                        "Failed to extract text from page %d of %s".formatted(pageNo, file), e);
            }
            return stripper.capturedRuns();
        }

        /**
         * Crop box is what a reader actually sees, so it - not the media box - defines the
         * page. A rotated page swaps the visible dimensions; the "DirAdj" text coordinates
         * are already rotation-corrected, so they agree with the swapped geometry.
         */
        private static PageGeometry geometryOf(PDPage page) {
            PDRectangle box = page.getCropBox() != null ? page.getCropBox() : page.getMediaBox();
            int rotation = ((page.getRotation() % 360) + 360) % 360;
            boolean quarterTurned = rotation == 90 || rotation == 270;
            float width = quarterTurned ? box.getHeight() : box.getWidth();
            float height = quarterTurned ? box.getWidth() : box.getHeight();
            return new PageGeometry(width, height);
        }

        private static PageKind classify(PDPage page, int charCount) {
            if (charCount >= SCANNED_CHAR_THRESHOLD) return PageKind.TEXT;
            if (hasLargeImage(page)) return PageKind.SCANNED;
            return charCount == 0 ? PageKind.EMPTY : PageKind.TEXT;
        }

        /**
         * Checks image dimensions from the XObject dictionary without decoding pixels -
         * decoding here would make ingestion many times slower for no benefit.
         */
        private static boolean hasLargeImage(PDPage page) {
            PDResources resources = page.getResources();
            if (resources == null) return false;
            for (COSName name : resources.getXObjectNames()) {
                try {
                    if (!resources.isImageXObject(name)) continue;
                    if (resources.getXObject(name) instanceof PDImageXObject image
                            && image.getWidth() >= LARGE_IMAGE_MIN_PIXELS
                            && image.getHeight() >= LARGE_IMAGE_MIN_PIXELS) {
                        return true;
                    }
                } catch (IOException | RuntimeException e) {
                    // A single unreadable XObject must not fail the page.
                    log.debug("Skipping unreadable XObject {}: {}", name, e.getMessage());
                }
            }
            return false;
        }

        @Override
        public void close() {
            try {
                document.close();
            } catch (IOException e) {
                log.warn("Failed to close PDF {}: {}", file, e.getMessage());
            }
        }
    }

    private record PageGeometry(float width, float height) {}
}
