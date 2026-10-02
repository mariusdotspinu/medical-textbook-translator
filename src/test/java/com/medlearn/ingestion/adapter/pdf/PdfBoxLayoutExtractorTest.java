package com.medlearn.ingestion.adapter.pdf;

import com.medlearn.ingestion.domain.OutlineNode;
import com.medlearn.ingestion.domain.PageKind;
import com.medlearn.ingestion.domain.PageLayout;
import com.medlearn.ingestion.domain.TextBlock;
import com.medlearn.ingestion.domain.port.LayoutExtractorPort;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitWidthDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the PDFBox adapter against a real generated PDF.
 *
 * <p>Generating the fixture rather than committing one keeps the test hermetic and lets it
 * assert on <em>known</em> coordinates - which is the only way to catch the coordinate-space
 * mistake this adapter is most exposed to: PDF drawing measures y upward from the page
 * bottom, while extraction reports it downward from the top.
 */
class PdfBoxLayoutExtractorTest {

    private static final float PAGE_HEIGHT = PDRectangle.LETTER.getHeight();   // 792pt
    private static final float TEXT_SIZE = 12f;

    private final PdfBoxLayoutExtractor extractor = new PdfBoxLayoutExtractor();
    private final PdfBoxOutlineReader outlineReader = new PdfBoxOutlineReader();

    @Test
    @DisplayName("probe reports page count and embedded metadata without full extraction")
    void probesMetadata(@TempDir Path dir) throws IOException {
        Path pdf = writeTextbook(dir, 3);

        LayoutExtractorPort.DocumentProbe probe = extractor.probe(pdf);

        assertThat(probe.pageCount()).isEqualTo(3);
        assertThat(probe.title()).isEqualTo("Test Medical Textbook");
        assertThat(probe.authors()).isEqualTo("A. Resident");
        assertThat(probe.encrypted()).isFalse();
    }

    @Test
    @DisplayName("extracted text keeps its words and lands in top-down page coordinates")
    void extractsTextWithTopDownCoordinates(@TempDir Path dir) throws IOException {
        Path pdf = writeTextbook(dir, 1);

        try (LayoutExtractorPort.Session session = extractor.open(pdf)) {
            PageLayout page = session.extractPage(1);

            assertThat(page.pageNo()).isEqualTo(1);
            assertThat(page.heightPt()).isEqualTo(PAGE_HEIGHT);
            assertThat(page.kind()).isEqualTo(PageKind.TEXT);
            assertThat(page.blocks()).isNotEmpty();

            TextBlock heading = page.blocks().getFirst();
            assertThat(heading.text()).isEqualTo("Chapter 1 Cardiology");

            // Drawn 60pt below the top of the page. Extraction must report y near 60 from
            // the top - not near 732, which is what leaking PDF-space y would produce.
            assertThat(heading.box().y()).isCloseTo(60f, org.assertj.core.data.Offset.offset(14f));
            assertThat(heading.box().x()).isCloseTo(50f, org.assertj.core.data.Offset.offset(2f));

            // And the documented conversion must take it back to where it was drawn.
            float drawnFromBottom = PAGE_HEIGHT - 60f - TEXT_SIZE;
            assertThat(heading.box().toPdfSpaceY(PAGE_HEIGHT))
                    .isCloseTo(drawnFromBottom, org.assertj.core.data.Offset.offset(14f));
        }
    }

    @Test
    @DisplayName("body text on distinct baselines becomes distinct blocks in reading order")
    void separatesBodyLines(@TempDir Path dir) throws IOException {
        Path pdf = writeTextbook(dir, 1);

        try (LayoutExtractorPort.Session session = extractor.open(pdf)) {
            PageLayout page = session.extractPage(1);

            assertThat(page.blocks().stream().map(TextBlock::text))
                    .containsSubsequence(
                            "Chapter 1 Cardiology",
                            "Troponin I rises within 3 to 4 hours.",
                            "Give aspirin 300 mg orally.");

            // Blocks are emitted top-to-bottom, which is what reading order depends on.
            List<Float> tops = page.blocks().stream().map(block -> block.box().y()).toList();
            assertThat(tops).isSorted();
        }
    }

    @Test
    @DisplayName("numbers and dosages survive extraction verbatim")
    void preservesDosagesExactly(@TempDir Path dir) throws IOException {
        // If a dosage is mangled before translation even starts, no downstream guard helps.
        Path pdf = writeTextbook(dir, 1);

        try (LayoutExtractorPort.Session session = extractor.open(pdf)) {
            String allText = String.join(" ",
                    session.extractPage(1).blocks().stream().map(TextBlock::text).toList());

            assertThat(allText).contains("300 mg").contains("3 to 4 hours");
        }
    }

    @Test
    @DisplayName("bookmark ranges end where the next non-descendant entry begins")
    void closesOutlineRangesAgainstTheNextSibling(@TempDir Path dir) throws IOException {
        Path pdf = writeTextbook(dir, 10);

        List<OutlineNode> outline = outlineReader.readOutline(pdf, 10);

        assertThat(outline).hasSize(2);

        OutlineNode chapterOne = outline.getFirst();
        assertThat(chapterOne.title()).isEqualTo("Chapter 1");
        assertThat(chapterOne.range().startPage()).isEqualTo(1);
        // Chapter 2 starts on page 6, so Chapter 1 must end on 5 - and must NOT be
        // truncated to page 2 by its own subsection, which starts on page 3.
        assertThat(chapterOne.range().endPage()).isEqualTo(5);

        assertThat(chapterOne.children()).singleElement().satisfies(section -> {
            assertThat(section.title()).isEqualTo("1.1 Anatomy");
            assertThat(section.range().startPage()).isEqualTo(3);
            assertThat(section.range().endPage()).isEqualTo(5);
        });

        OutlineNode chapterTwo = outline.get(1);
        assertThat(chapterTwo.range().startPage()).isEqualTo(6);
        // Last entry runs to the end of the book.
        assertThat(chapterTwo.range().endPage()).isEqualTo(10);
    }

    @Test
    @DisplayName("a document with no bookmarks yields an empty outline rather than failing")
    void returnsEmptyOutlineWhenAbsent(@TempDir Path dir) throws IOException {
        Path pdf = dir.resolve("plain.pdf");
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage(PDRectangle.LETTER));
            document.save(pdf.toFile());
        }

        assertThat(outlineReader.readOutline(pdf, 1)).isEmpty();
    }

    @Test
    @DisplayName("a page with no text at all is reported as EMPTY, not as text")
    void classifiesBlankPages(@TempDir Path dir) throws IOException {
        Path pdf = dir.resolve("blank.pdf");
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage(PDRectangle.LETTER));
            document.save(pdf.toFile());
        }

        try (LayoutExtractorPort.Session session = extractor.open(pdf)) {
            PageLayout page = session.extractPage(1);
            assertThat(page.kind()).isEqualTo(PageKind.EMPTY);
            assertThat(page.blocks()).isEmpty();
        }
    }

    @Test
    @DisplayName("concurrent sessions over one file do not interfere")
    void supportsParallelSessionsOverTheSameFile(@TempDir Path dir) throws Exception {
        // The ingest pipeline relies on this: PDDocument is not thread-safe, so each worker
        // opens its own session on the same path.
        Path pdf = writeTextbook(dir, 8);

        List<java.util.concurrent.CompletableFuture<String>> futures = List.of(1, 3, 5, 7).stream()
                .map(pageNo -> java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                    try (LayoutExtractorPort.Session session = extractor.open(pdf)) {
                        return session.extractPage(pageNo).blocks().getFirst().text();
                    }
                }))
                .toList();

        for (var future : futures) {
            assertThat(future.get()).isEqualTo("Chapter 1 Cardiology");
        }
    }

    // --- fixture -----------------------------------------------------------

    /**
     * Writes a small textbook: every page carries a heading and two body lines, and the
     * bookmark tree is Chapter 1 (p1) > 1.1 Anatomy (p3), Chapter 2 (p6).
     */
    private static Path writeTextbook(Path dir, int pageCount) throws IOException {
        Path pdf = dir.resolve("textbook.pdf");
        try (PDDocument document = new PDDocument()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

            for (int i = 0; i < pageCount; i++) {
                PDPage page = new PDPage(PDRectangle.LETTER);
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    writeLine(content, font, "Chapter 1 Cardiology", 50, 60);
                    writeLine(content, font, "Troponin I rises within 3 to 4 hours.", 50, 100);
                    writeLine(content, font, "Give aspirin 300 mg orally.", 50, 130);
                }
            }

            document.getDocumentInformation().setTitle("Test Medical Textbook");
            document.getDocumentInformation().setAuthor("A. Resident");

            if (pageCount >= 6) addOutline(document);
            document.save(pdf.toFile());
        }
        return pdf;
    }

    /** @param fromTop distance below the top of the page, converted to PDF space here */
    private static void writeLine(
            PDPageContentStream content, PDType1Font font, String text, float x, float fromTop)
            throws IOException {
        content.beginText();
        content.setFont(font, TEXT_SIZE);
        content.newLineAtOffset(x, PAGE_HEIGHT - fromTop - TEXT_SIZE);
        content.showText(text);
        content.endText();
    }

    private static void addOutline(PDDocument document) {
        PDDocumentOutline outline = new PDDocumentOutline();
        document.getDocumentCatalog().setDocumentOutline(outline);

        PDOutlineItem chapterOne = outlineItem("Chapter 1", document, 0);
        PDOutlineItem section = outlineItem("1.1 Anatomy", document, 2);
        chapterOne.addLast(section);
        outline.addLast(chapterOne);

        outline.addLast(outlineItem("Chapter 2", document, 5));
    }

    private static PDOutlineItem outlineItem(String title, PDDocument document, int pageIndex) {
        PDOutlineItem item = new PDOutlineItem();
        item.setTitle(title);
        PDPageFitWidthDestination destination = new PDPageFitWidthDestination();
        destination.setPage(document.getPage(pageIndex));
        item.setDestination(destination);
        return item;
    }
}
