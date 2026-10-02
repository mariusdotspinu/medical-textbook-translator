package com.medlearn.translation.adapter.render;

import com.medlearn.ingestion.domain.PageLayout;
import com.medlearn.ingestion.domain.TextBlock;
import com.medlearn.ingestion.domain.TextRole;
import com.medlearn.shared.DomainError;
import com.medlearn.shared.FontUnavailableException;
import com.medlearn.shared.LanguageTag;
import com.medlearn.shared.Script;
import com.medlearn.translation.domain.RenderMode;
import com.medlearn.shared.port.FontProviderPort;
import com.medlearn.translation.domain.port.PdfRendererPort;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Typesets a clean new document from the translated text.
 *
 * <p>Used two ways: as a render mode in its own right, and as the per-page fallback when the
 * overlay renderer cannot fit a page's text into the original boxes. The second use is why
 * this is a collaborator of {@link OverlayPdfRenderer} rather than an alternative to it.
 *
 * <p>Built on OpenHTMLtoPDF because paragraph flow, page breaking, widow control and font
 * fallback are all solved problems in a CSS engine and all tedious to hand-code against
 * PDFBox's drawing primitives.
 *
 * <p>Original page numbers are printed alongside the text. In reflow mode the layout is
 * gone, so this is the reader's only way back to the figure the paragraph is talking about.
 */
@Component
public class ReflowPdfRenderer implements PdfRendererPort {

    private final FontProviderPort fonts;

    public ReflowPdfRenderer(FontProviderPort fonts) {
        this.fonts = fonts;
    }

    @Override
    public RenderMode mode() {
        return RenderMode.REFLOW;
    }

    @Override
    public RenderResult render(RenderRequest request) {
        Path regular = fonts.regularFont(request.targetLanguage())
                .orElseThrow(() -> new com.medlearn.shared.FontUnavailableException(new DomainError.FontUnavailable(
                        request.targetLanguage(), fonts.searchLocationsDescription())));
        Path bold = fonts.boldFont(request.targetLanguage()).orElse(regular);

        String html = buildHtml(request);

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.useFont(regular.toFile(), "MedlearnBody");
            builder.useFont(bold.toFile(), "MedlearnHeading");
            builder.withHtmlContent(html, null);
            builder.toStream(out);
            builder.run();

            byte[] pdf = out.toByteArray();
            return new RenderResult(pdf, countPages(pdf), List.of());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not render reflowed PDF", e);
        }
    }

    private String buildHtml(RenderRequest request) {
        Script script = request.targetLanguage().script();

        StringBuilder html = new StringBuilder(4096);
        // OpenHTMLtoPDF parses XHTML, not HTML: void elements must be self-closed or the
        // whole render fails with a SAX error rather than degrading.
        html.append("<!DOCTYPE html><html lang=\"")
                .append(escape(request.targetLanguage().tag()))
                .append("\"><head><meta charset=\"utf-8\"/><title>")
                .append(escape(request.documentTitle()))
                .append("</title><style>")
                .append(css(script))
                .append("</style></head><body>");

        html.append("<h1 class=\"doc-title\">").append(escape(request.documentTitle())).append("</h1>");

        for (PageLayout page : request.pages()) {
            List<TextBlock> blocks = page.blocks().stream().filter(TextBlock::translatable).toList();
            if (blocks.isEmpty()) continue;

            html.append("<div class=\"page-marker\">p. ").append(page.pageNo()).append("</div>");
            for (TextBlock block : blocks) {
                String text = escape(request.textFor(block));
                if (block.role() == TextRole.HEADING) {
                    html.append("<h2>").append(text).append("</h2>");
                } else if (block.role() == TextRole.CAPTION) {
                    html.append("<p class=\"caption\">").append(text).append("</p>");
                } else {
                    html.append("<p>").append(text).append("</p>");
                }
            }
        }

        return html.append("</body></html>").toString();
    }

    private static String css(Script script) {
        // CJK has no inter-word spaces, so the engine must be allowed to break between
        // characters; applying the same rule to Latin would split words mid-stem.
        String breaking = script == Script.CJK
                ? "word-break: break-all; line-break: strict;"
                : "word-break: normal; hyphens: auto;";

        return """
                @page { size: A4; margin: 2.2cm 2cm; }
                body { font-family: "MedlearnBody", sans-serif; font-size: 10.5pt;
                       line-height: 1.5; color: #111; %s }
                h1.doc-title { font-family: "MedlearnHeading", sans-serif; font-size: 18pt;
                       margin: 0 0 18pt 0; }
                h2 { font-family: "MedlearnHeading", sans-serif; font-size: 13pt;
                       margin: 16pt 0 6pt 0; page-break-after: avoid; }
                p { margin: 0 0 7pt 0; text-align: justify; orphans: 2; widows: 2; }
                p.caption { font-size: 9pt; color: #444; font-style: italic; }
                .page-marker { font-size: 7.5pt; color: #888; margin: 12pt 0 4pt 0;
                       border-top: 0.5pt solid #ddd; padding-top: 3pt; }
                """.formatted(breaking);
    }

    private static int countPages(byte[] pdf) {
        try (var document = org.apache.pdfbox.Loader.loadPDF(pdf)) {
            return document.getNumberOfPages();
        } catch (IOException e) {
            return 0;
        }
    }

    /** Translated medical text routinely contains &lt;, &gt; and &amp; - escape, never trust. */
    private static String escape(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

}
