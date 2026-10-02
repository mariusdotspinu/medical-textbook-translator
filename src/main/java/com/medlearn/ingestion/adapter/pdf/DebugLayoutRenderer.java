package com.medlearn.ingestion.adapter.pdf;

import com.medlearn.ingestion.domain.PageLayout;
import com.medlearn.ingestion.domain.TextBlock;
import com.medlearn.ingestion.domain.TextRole;
import com.medlearn.ingestion.domain.port.DebugRendererPort;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.io.RandomAccessReadBufferedFile;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDPageContentStream.AppendMode;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Draws extracted blocks over the original page: one box per block, numbered in reading
 * order and coloured by column.
 *
 * <p>This is the fastest way to find an extraction bug. A two-column page whose numbering
 * runs 1,2,3... straight down the left column and then continues down the right is correct;
 * numbering that alternates across the gutter is the merge defect, visible instantly instead
 * of inferred from odd translations.
 *
 * <p>Boxes are drawn in PDF space, so every rectangle goes through
 * {@code BlockBox.toPdfSpaceY} - the single conversion between extraction's top-down
 * coordinates and PDF's bottom-up ones.
 */
@Component
public class DebugLayoutRenderer implements DebugRendererPort {

    private static final float BOX_LINE_WIDTH = 0.6f;
    private static final float LABEL_SIZE = 6f;

    /** Column 0 blocks. */
    private static final Color LEFT_COLUMN = new Color(0, 90, 200);
    /** Column 1 blocks - a different hue is what makes a cross-gutter merge obvious. */
    private static final Color RIGHT_COLUMN = new Color(220, 110, 0);
    private static final Color HEADING = new Color(0, 150, 60);
    /** Running heads and folios: excluded from translation. */
    private static final Color FURNITURE = new Color(200, 0, 0);

    @Override
    public byte[] renderLayoutOverlay(Path pdfFile, List<PageLayout> layouts) {
        if (layouts.isEmpty()) throw new IllegalArgumentException("no pages to render");

        try (PDDocument source = Loader.loadPDF(
                     new RandomAccessReadBufferedFile(pdfFile.toFile()),
                     IOUtils.createTempFileOnlyStreamCache());
             PDDocument output = new PDDocument()) {

            PDType1Font labelFont = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

            for (PageLayout layout : layouts) {
                int index = layout.pageNo() - 1;
                if (index < 0 || index >= source.getNumberOfPages()) continue;

                // importPage keeps the original content as the backdrop, so the boxes can be
                // compared against the real text rather than floating in isolation.
                PDPage page = output.importPage(source.getPage(index));
                drawOverlay(output, page, layout, labelFont);
            }

            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            output.save(bytes);
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new PdfAccessException("Could not render debug layout for " + pdfFile, e);
        }
    }

    private void drawOverlay(
            PDDocument output, PDPage page, PageLayout layout, PDType1Font font) throws IOException {

        float pageHeight = layout.heightPt();

        try (PDPageContentStream content =
                     new PDPageContentStream(output, page, AppendMode.APPEND, true, true)) {

            for (TextBlock block : layout.blocks()) {
                Color color = colorFor(block);
                float x = block.box().x();
                float y = block.box().toPdfSpaceY(pageHeight);
                float width = Math.max(1f, block.box().width());
                float height = Math.max(1f, block.box().height());

                content.setStrokingColor(color);
                content.setLineWidth(BOX_LINE_WIDTH);
                content.addRect(x, y, width, height);
                content.stroke();

                // Reading-order index, placed just left of the box so it never hides text.
                content.beginText();
                content.setNonStrokingColor(color);
                content.setFont(font, LABEL_SIZE);
                content.newLineAtOffset(Math.max(1f, x - 16f), y + height - LABEL_SIZE);
                content.showText(String.valueOf(block.orderInPage()));
                content.endText();
            }

            drawLegend(content, font, layout);
        }
    }

    /** Summary strip at the page foot: what the colours mean and what was detected. */
    private void drawLegend(
            PDPageContentStream content, PDType1Font font, PageLayout layout) throws IOException {

        String summary = "page %d  |  %s  |  %d blocks  |  %d column(s)  |  %d chars"
                .formatted(layout.pageNo(), layout.kind(), layout.blocks().size(),
                        layout.columnCount(), layout.charCount());

        content.beginText();
        content.setNonStrokingColor(Color.BLACK);
        content.setFont(font, 7f);
        content.newLineAtOffset(10f, 10f);
        content.showText(summary + "   [blue=col0  orange=col1  green=heading  red=furniture]");
        content.endText();
    }

    private static Color colorFor(TextBlock block) {
        return switch (block.role()) {
            case HEADING -> HEADING;
            case RUNNING_HEAD, FOLIO, REFERENCE -> FURNITURE;
            case BODY, CAPTION, FOOTNOTE -> block.columnIndex() == 0 ? LEFT_COLUMN : RIGHT_COLUMN;
        };
    }
}
