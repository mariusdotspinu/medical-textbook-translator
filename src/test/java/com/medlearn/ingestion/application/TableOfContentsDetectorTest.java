package com.medlearn.ingestion.application;

import com.medlearn.ingestion.adapter.pdf.PdfBoxLayoutExtractor;
import com.medlearn.ingestion.domain.HeaderFooterFilter;
import com.medlearn.ingestion.domain.PageLayout;
import com.medlearn.ingestion.domain.port.LayoutExtractorPort;
import com.medlearn.ingestion.domain.port.PageLayoutRepository;
import com.medlearn.jobs.application.JobCoordinator;
import com.medlearn.library.domain.Chapter;
import com.medlearn.library.domain.ChapterSource;
import com.medlearn.platform.config.IngestionProperties;
import com.medlearn.shared.JobId;
import com.medlearn.shared.PageRange;
import com.medlearn.shared.TextbookId;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * End to end on a generated PDF without bookmarks: real extraction, the real furniture pass,
 * and the printed TOC turned into chapters.
 */
class TableOfContentsDetectorTest {

    private static final float LEFT = 72f;
    private static final float INDENT = 96f;

    /** Front matter: a title page and the contents page, so printed page 1 is PDF page 3. */
    private static final int FRONT_MATTER = 2;
    private static final int BODY_PAGES = 16;

    private final PdfBoxLayoutExtractor extractor = new PdfBoxLayoutExtractor();
    private final PageLayoutRepository pageLayouts = mock(PageLayoutRepository.class);
    private final TableOfContentsDetector detector = new TableOfContentsDetector(
            extractor, pageLayouts, new ChapterDetectionService(), new IngestionProperties(2, 100));

    private final TextbookId bookId = TextbookId.newId();

    @Test
    @DisplayName("a book without bookmarks gets its chapters from the printed contents page")
    void chaptersFromPrintedContents(@TempDir Path dir) throws IOException {
        Path pdf = writeBook(dir, true);
        storeExtractedPages(pdf);

        var detected = detector.detect(bookId, pdf, FRONT_MATTER + BODY_PAGES, progress()).orElseThrow();

        assertThat(detected.source()).isEqualTo(ChapterSource.TOC);
        assertThat(detected.chapters()).extracting(Chapter::title).containsExactly(
                "1 Heart Failure", "1.1 Pathophysiology", "1.2 Treatment",
                "2 Arrhythmias", "3 Valvular Disease");
        // Printed page 1 is PDF page 3; a chapter runs to the page before the next one.
        assertThat(detected.chapters()).extracting(Chapter::range).containsExactly(
                PageRange.of(3, 8), PageRange.of(3, 5), PageRange.of(6, 8),
                PageRange.of(9, 13), PageRange.of(14, 18));
        assertThat(detected.chapters().get(1).parentId()).contains(detected.chapters().getFirst().id());
    }

    @Test
    @DisplayName("without printed page numbers, chapter headings on their pages place the TOC")
    void placesByTitlesWithoutFolios(@TempDir Path dir) throws IOException {
        Path pdf = writeBook(dir, false);
        storeExtractedPages(pdf);

        var detected = detector.detect(bookId, pdf, FRONT_MATTER + BODY_PAGES, progress()).orElseThrow();

        assertThat(detected.chapters().getFirst().range()).isEqualTo(PageRange.of(3, 8));
        assertThat(detected.chapters().getLast().range()).isEqualTo(PageRange.of(14, 18));
    }

    /** Extracts and classifies the pages the way ingestion does, and serves them back. */
    private void storeExtractedPages(Path pdf) {
        int pageCount = FRONT_MATTER + BODY_PAGES;
        HeaderFooterFilter furniture = new HeaderFooterFilter();
        List<PageLayout> raw = new ArrayList<>();
        try (LayoutExtractorPort.Session session = extractor.open(pdf)) {
            for (int page = 1; page <= pageCount; page++) {
                PageLayout layout = session.extractPage(page);
                furniture.observe(layout);
                raw.add(layout);
            }
        }
        Map<Integer, PageLayout> stored = new TreeMap<>();
        raw.forEach(page -> stored.put(page.pageNo(), furniture.apply(page, furniture.repeatedKeys())));

        when(pageLayouts.findRange(eq(bookId), any())).thenAnswer(invocation -> {
            PageRange range = invocation.getArgument(1);
            return stored.values().stream().filter(page -> range.contains(page.pageNo())).toList();
        });
    }

    private static Path writeBook(Path dir, boolean withFolios) throws IOException {
        Path file = dir.resolve(withFolios ? "with-folios.pdf" : "without-folios.pdf");
        PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

        try (PDDocument document = new PDDocument()) {
            page(document, font, List.of(new Line(LEFT, 700, 20, bold, "Clinical Cardiology")), null);
            page(document, font, List.of(
                    new Line(LEFT, 720, 16, bold, "Contents"),
                    new Line(LEFT, 690, 11, font, "Preface . . . . . . . . . . . . . . . . . . . . . . . . . . vii"),
                    new Line(LEFT, 672, 11, font, "1 Heart Failure . . . . . . . . . . . . . . . . . . . . . . . 1"),
                    new Line(INDENT, 654, 11, font, "1.1 Pathophysiology . . . . . . . . . . . . . . . . . . 1"),
                    new Line(INDENT, 636, 11, font, "1.2 Treatment . . . . . . . . . . . . . . . . . . . . . . 4"),
                    new Line(LEFT, 618, 11, font, "2 Arrhythmias . . . . . . . . . . . . . . . . . . . . . . . 7"),
                    new Line(LEFT, 600, 11, font, "3 Valvular Disease . . . . . . . . . . . . . . . . . . . 12")),
                    null);

            Map<Integer, String> headings = Map.of(
                    1, "1 Heart Failure", 4, "1.2 Treatment", 7, "2 Arrhythmias", 12, "3 Valvular Disease");
            for (int printed = 1; printed <= BODY_PAGES; printed++) {
                List<Line> lines = new ArrayList<>();
                float y = 700;
                if (headings.containsKey(printed)) {
                    lines.add(new Line(LEFT, y, 18, bold, headings.get(printed)));
                    y -= 30;
                }
                for (int i = 0; i < 8; i++, y -= 16) {
                    lines.add(new Line(LEFT, y, 11, font,
                            "Body text about cardiology on printed page " + printed + ", line " + i + "."));
                }
                page(document, font, lines, withFolios ? String.valueOf(printed) : null);
            }
            document.save(file.toFile());
        }
        return file;
    }

    private record Line(float x, float y, float size, PDType1Font font, String text) {}

    private static void page(PDDocument document, PDType1Font font, List<Line> lines, String folio)
            throws IOException {
        PDPage page = new PDPage(PDRectangle.LETTER);
        document.addPage(page);
        try (PDPageContentStream content = new PDPageContentStream(document, page)) {
            for (Line line : lines) write(content, line.font(), line.size(), line.x(), line.y(), line.text());
            if (folio != null) write(content, font, 10, 300, 30, folio);
        }
    }

    private static void write(PDPageContentStream content, PDType1Font font, float size,
                              float x, float y, String text) throws IOException {
        content.beginText();
        content.setFont(font, size);
        content.newLineAtOffset(x, y);
        content.showText(text);
        content.endText();
    }

    private static JobCoordinator.ProgressSink progress() {
        return new JobCoordinator.ProgressSink() {
            @Override public JobId jobId() { return JobId.newId(); }
            @Override public void total(int totalUnits) {}
            @Override public void advance(int completedUnits) {}
            @Override public void phase(String phase) {}
            @Override public boolean cancelled() { return false; }
        };
    }
}
