package com.medlearn.translation.adapter.render;

import com.medlearn.ingestion.domain.BlockBox;
import com.medlearn.ingestion.domain.PageLayout;
import com.medlearn.ingestion.domain.TextBlock;
import com.medlearn.shared.DomainError;
import com.medlearn.shared.Script;
import com.medlearn.translation.domain.QualityFinding;
import com.medlearn.translation.domain.RenderMode;
import com.medlearn.translation.domain.ScriptAwareLineBreaker;
import com.medlearn.shared.port.FontProviderPort;
import com.medlearn.translation.domain.port.PdfRendererPort;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.io.RandomAccessReadBufferedFile;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDPageContentStream.AppendMode;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Replaces the words on each page while leaving everything else exactly as it was.
 *
 * <p>Figures, tables, vector diagrams, rules and page furniture are never touched: the
 * original page is imported whole and only the translated text blocks are masked and
 * redrawn. The output stays vector and searchable, which a rasterising approach would lose.
 *
 * <p><b>Masking.</b> Each replaced block is covered with a rectangle filled with a colour
 * sampled from the page itself, just outside the block's own box. Sampling rather than
 * assuming white is what lets tinted sidebars and shaded tables survive - and medical
 * textbooks are full of both.
 *
 * <p><b>Fitting.</b> Translations run 15-30% longer than English in Romance and Slavic
 * languages, so the text often will not fit the box it replaces. A fixed ladder is tried -
 * rewrap, shrink to a floor, tighten leading - and any page that still will not fit is
 * rendered by {@link ReflowPdfRenderer} instead and reported as a finding. Overflowing text
 * onto neighbouring content, or silently clipping it, would both be worse than a page that
 * admits it had to change shape.
 */
@Component
public class OverlayPdfRenderer implements PdfRendererPort {

    private static final Logger log = LoggerFactory.getLogger(OverlayPdfRenderer.class);

    /**
     * Font may shrink to this fraction of the original before the page is reflowed instead.
     *
     * <p>0.80 rather than a more cautious 0.85 for a specific reason: a 25% longer
     * translation stays on one line only at a width scale of 1/1.25 = 0.80. Stopping at 0.85
     * means every single-line block wraps to two lines and forces a reflow that 5% less type
     * would have avoided. Below 0.80 the size difference starts to read as an error on the
     * page, so that is where the ladder stops.
     */
    private static final float MIN_FONT_SCALE = 0.80f;
    private static final float FONT_SHRINK_STEP = 0.02f;

    /** Normal leading, and the tightest allowed before giving up. */
    private static final float DEFAULT_LEADING = 1.18f;
    private static final float TIGHT_LEADING = 0.92f * 1.18f;

    /** Mask is grown slightly past the glyph box to cover antialiased edges. */
    private static final float MASK_PADDING_PT = 1.2f;

    /**
     * Extra mask height below the box, as a fraction of font size.
     *
     * <p>The box reported by extraction spans the font's height, which does not reach the
     * bottom of a descender. Without this, the tails of g, j, p, q and y survive the mask and
     * speckle the page beneath the replacement text.
     */
    private static final float DESCENDER_PADDING_RATIO = 0.28f;

    /** Gap kept between a grown block and the next one, so replacements never collide. */
    private static final float NEIGHBOUR_MARGIN_PT = 2f;

    /** 72 DPI makes one raster pixel equal one PDF point, so no scaling maths is needed. */
    private static final float SAMPLE_DPI = 72f;

    private final FontProviderPort fonts;
    private final ReflowPdfRenderer reflowFallback;

    public OverlayPdfRenderer(FontProviderPort fonts, ReflowPdfRenderer reflowFallback) {
        this.fonts = fonts;
        this.reflowFallback = reflowFallback;
    }

    @Override
    public RenderMode mode() {
        return RenderMode.OVERLAY;
    }

    @Override
    public RenderResult render(RenderRequest request) {
        Path fontFile = fonts.regularFont(request.targetLanguage())
                .orElseThrow(() -> new com.medlearn.shared.FontUnavailableException(
                        new DomainError.FontUnavailable(
                                request.targetLanguage(), fonts.searchLocationsDescription())));

        List<QualityFinding> findings = new ArrayList<>();

        try (PDDocument source = Loader.loadPDF(
                     new RandomAccessReadBufferedFile(request.sourcePdf().toFile()),
                     IOUtils.createTempFileOnlyStreamCache());
             PDDocument output = new PDDocument()) {

            // Subset-embedded: only the glyphs actually used are written, which keeps a
            // translated chapter small even with a large Unicode face.
            PDType0Font font;
            try (var fontStream = java.nio.file.Files.newInputStream(fontFile)) {
                font = PDType0Font.load(output, fontStream, true);
            }
            PDFRenderer rasterizer = new PDFRenderer(source);
            Script script = request.targetLanguage().script();

            List<PageLayout> reflowQueue = new ArrayList<>();

            for (PageLayout page : request.pages()) {
                int pageIndex = page.pageNo() - 1;
                if (pageIndex < 0 || pageIndex >= source.getNumberOfPages()) continue;

                PDPage sourcePage = source.getPage(pageIndex);
                List<Replacement> replacements = planReplacements(page, request, font, script);

                boolean rotated = ((sourcePage.getRotation() % 360) + 360) % 360 != 0;
                boolean unfittable = replacements.stream().anyMatch(Replacement::unfittable);

                if (rotated || unfittable) {
                    // Rotated pages would need a full coordinate transform to overlay
                    // correctly; reflowing is honest rather than approximately wrong.
                    replacements.stream()
                            .filter(Replacement::unfittable)
                            .forEach(replacement -> findings.add(
                                    new QualityFinding.Overflow(replacement.block().id(), page.pageNo())));
                    reflowQueue.add(page);
                    continue;
                }

                drainReflowQueue(output, request, reflowQueue);

                PDPage imported = output.importPage(sourcePage);
                BufferedImage raster = rasterizer.renderImageWithDPI(pageIndex, SAMPLE_DPI);
                drawReplacements(output, imported, page, replacements, raster, font);
            }

            drainReflowQueue(output, request, reflowQueue);

            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            output.save(bytes);
            return new RenderResult(bytes.toByteArray(), output.getNumberOfPages(), findings);

        } catch (IOException e) {
            throw new UncheckedIOException("Could not render overlay PDF", e);
        }
    }

    /** Works out, per block, whether and how the translation fits its original box. */
    private List<Replacement> planReplacements(
            PageLayout page, RenderRequest request, PDType0Font font, Script script) {

        List<Replacement> replacements = new ArrayList<>();

        for (TextBlock block : page.blocks()) {
            if (!block.translatable()) continue;      // running heads, folios: left untouched

            String translated = request.translations().get(block.id());
            if (translated == null || translated.isBlank() || translated.equals(block.text())) {
                continue;                              // nothing to replace
            }

            replacements.add(fit(block, translated, font, script, availableHeight(block, page)));
        }
        return replacements;
    }

    /**
     * How much vertical room a block may actually use.
     *
     * <p>Not simply the block's own box. A block's box bounds its glyphs tightly, so a
     * translation even slightly longer than its source could never fit - which would reflow
     * essentially every page. In reality there is whitespace below a paragraph before the
     * next one starts, and growing into it is invisible to the reader.
     *
     * <p>The budget therefore runs to the top of the next block in the same column, less a
     * small margin so the two never touch. The last block on a page may run to the bottom
     * margin.
     */
    private static float availableHeight(TextBlock block, PageLayout page) {
        float ownBottom = block.box().bottom();
        float nextTop = Float.MAX_VALUE;

        for (TextBlock other : page.blocks()) {
            if (other == block || other.columnIndex() != block.columnIndex()) continue;
            float otherTop = other.box().y();
            if (otherTop >= ownBottom - 0.5f && otherTop < nextTop) nextTop = otherTop;
        }

        float limit = nextTop == Float.MAX_VALUE ? page.heightPt() * 0.94f : nextTop;
        float available = limit - block.box().y() - NEIGHBOUR_MARGIN_PT;
        return Math.max(block.box().height(), available);
    }

    /**
     * The fitting ladder: original size, then progressively smaller, then tighter leading.
     *
     * @return a plan, possibly marked unfittable
     */
    private Replacement fit(
            TextBlock block, String text, PDType0Font font, Script script, float heightBudget) {
        float originalSize = Math.max(4f, block.font().sizePt());
        float width = block.box().width();
        float height = heightBudget;

        for (float leading : new float[]{DEFAULT_LEADING, TIGHT_LEADING}) {
            for (float scale = 1f; scale >= MIN_FONT_SCALE - 1e-4; scale -= FONT_SHRINK_STEP) {
                float size = originalSize * scale;
                List<String> lines = ScriptAwareLineBreaker.wrap(
                        text, script, width, candidate -> stringWidth(font, candidate, size));

                if (lines.isEmpty()) continue;
                float needed = lines.size() * size * leading;

                // One line always fits vertically: a single-line block that grew wider is
                // handled by the wrap above, not by rejecting the page.
                if (needed <= height + size * 0.35f || lines.size() == 1) {
                    return new Replacement(block, lines, size, size * leading, false);
                }
            }
        }

        List<String> lines = ScriptAwareLineBreaker.wrap(
                text, script, width,
                candidate -> stringWidth(font, candidate, originalSize * MIN_FONT_SCALE));
        return new Replacement(block, lines, originalSize * MIN_FONT_SCALE,
                originalSize * MIN_FONT_SCALE * TIGHT_LEADING, true);
    }

    private static double stringWidth(PDType0Font font, String text, float size) {
        try {
            return font.getStringWidth(text) / 1000f * size;
        } catch (IOException | IllegalArgumentException e) {
            // Unmappable glyph: estimate rather than fail the whole chapter.
            return text.length() * size * 0.5;
        }
    }

    private void drawReplacements(
            PDDocument output, PDPage page, PageLayout layout,
            List<Replacement> replacements, BufferedImage raster, PDType0Font font)
            throws IOException {

        if (replacements.isEmpty()) return;
        float pageHeight = layout.heightPt();

        try (PDPageContentStream content =
                     new PDPageContentStream(output, page, AppendMode.APPEND, true, true)) {

            // Two passes over the page, and the order is not optional. A block may grow
            // downwards into the whitespace above its neighbour, so if each block were
            // masked and drawn in turn, the next block's mask would paint over the previous
            // block's freshly drawn second line. Every mask therefore goes down first.
            Map<String, Color> textColors = new HashMap<>();

            for (Replacement replacement : replacements) {
                BlockBox box = replacement.block().box();
                Color background = sampleBackground(raster, box, layout);
                textColors.put(replacement.block().id(),
                        sampleTextColor(raster, box, layout, background));

                float descender = replacement.block().font().sizePt() * DESCENDER_PADDING_RATIO;
                content.setNonStrokingColor(background);
                content.addRect(
                        box.x() - MASK_PADDING_PT,
                        box.toPdfSpaceY(pageHeight) - MASK_PADDING_PT - descender,
                        box.width() + MASK_PADDING_PT * 2,
                        box.height() + MASK_PADDING_PT * 2 + descender);
                content.fill();
            }

            for (Replacement replacement : replacements) {
                BlockBox box = replacement.block().box();
                content.setNonStrokingColor(textColors.get(replacement.block().id()));

                float topInPdfSpace = pageHeight - box.y();
                float baseline = topInPdfSpace - replacement.fontSize() * 0.82f;

                for (String line : replacement.lines()) {
                    content.beginText();
                    content.setFont(font, replacement.fontSize());
                    content.newLineAtOffset(box.x(), baseline);
                    content.showText(sanitize(line));
                    content.endText();
                    baseline -= replacement.leading();
                }
            }
        }
    }

    /**
     * Background colour taken from just outside the block, so tinted panels and shaded table
     * rows are preserved rather than punched out with white rectangles.
     */
    private static Color sampleBackground(BufferedImage raster, BlockBox box, PageLayout layout) {
        float scale = raster.getWidth() / layout.widthPt();
        List<Color> samples = new ArrayList<>(8);

        float[][] points = {
                {box.x() - 3f, box.centerY()},
                {box.right() + 3f, box.centerY()},
                {box.centerX(), box.y() - 3f},
                {box.centerX(), box.bottom() + 3f},
                {box.x() - 3f, box.y() - 3f},
                {box.right() + 3f, box.bottom() + 3f},
        };
        for (float[] point : points) {
            colorAt(raster, point[0] * scale, point[1] * scale).ifPresent(samples::add);
        }
        return mostCommon(samples).orElse(Color.WHITE);
    }

    /**
     * Approximates the original text colour by the darkest pixel inside the box, so coloured
     * headings do not come back as flat black. Falls back to black when the box is light
     * throughout, and to white when the background is dark (light-on-dark panels).
     */
    private static Color sampleTextColor(
            BufferedImage raster, BlockBox box, PageLayout layout, Color background) {

        float scale = raster.getWidth() / layout.widthPt();
        Color darkest = null;
        int darkestLuminance = Integer.MAX_VALUE;

        for (float y = box.y(); y < box.bottom(); y += 1.5f) {
            for (float x = box.x(); x < box.right(); x += 2f) {
                var sample = colorAt(raster, x * scale, y * scale);
                if (sample.isEmpty()) continue;
                int luminance = luminanceOf(sample.get());
                if (luminance < darkestLuminance) {
                    darkestLuminance = luminance;
                    darkest = sample.get();
                }
            }
        }

        if (darkest == null) return Color.BLACK;
        // Nearly the same as the background means no glyphs were sampled; pick by contrast.
        if (Math.abs(luminanceOf(background) - darkestLuminance) < 30) {
            return luminanceOf(background) > 128 ? Color.BLACK : Color.WHITE;
        }
        return darkest;
    }

    private static java.util.Optional<Color> colorAt(BufferedImage raster, float x, float y) {
        int px = Math.round(x);
        int py = Math.round(y);
        if (px < 0 || py < 0 || px >= raster.getWidth() || py >= raster.getHeight()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new Color(raster.getRGB(px, py), false));
    }

    private static java.util.Optional<Color> mostCommon(List<Color> samples) {
        if (samples.isEmpty()) return java.util.Optional.empty();
        Map<Integer, Integer> counts = new HashMap<>();
        for (Color sample : samples) counts.merge(sample.getRGB(), 1, Integer::sum);
        return counts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(entry -> new Color(entry.getKey(), false));
    }

    private static int luminanceOf(Color color) {
        return (color.getRed() * 299 + color.getGreen() * 587 + color.getBlue() * 114) / 1000;
    }

    /**
     * Drops characters the embedded font cannot encode.
     *
     * <p>{@code showText} throws on an unmappable glyph, which would abort the whole
     * chapter. Losing a rare symbol is survivable; losing the PDF is not. The replacement
     * character makes the loss visible rather than silent.
     */
    private static String sanitize(String line) {
        StringBuilder safe = new StringBuilder(line.length());
        for (char character : line.toCharArray()) {
            if (character == '\n' || character == '\r' || character == '\t') {
                safe.append(' ');
            } else if (character < 32) {
                safe.append(' ');
            } else {
                safe.append(character);
            }
        }
        return safe.toString();
    }

    /** Renders queued pages through the reflow renderer and appends them to the output. */
    private void drainReflowQueue(
            PDDocument output, RenderRequest request, List<PageLayout> queue) throws IOException {

        if (queue.isEmpty()) return;

        RenderResult reflowed = reflowFallback.render(new RenderRequest(
                request.sourcePdf(), List.copyOf(queue), request.translations(),
                request.targetLanguage(), request.documentTitle()));
        queue.clear();

        try (PDDocument reflowDocument = Loader.loadPDF(reflowed.pdf())) {
            for (PDPage page : reflowDocument.getPages()) {
                output.importPage(page);
            }
        }
        log.info("Reflowed {} page(s) that could not be fitted in place", reflowed.pageCount());
    }

    /**
     * @param unfittable text that will not fit even at the floor size and tightest leading;
     *                   the page is reflowed instead
     */
    private record Replacement(
            TextBlock block, List<String> lines, float fontSize, float leading, boolean unfittable) {}
}
