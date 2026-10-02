package com.medlearn.learning.adapter.render;

import com.medlearn.learning.domain.StudyGuide;
import com.medlearn.learning.domain.port.StudyGuideRendererPort;
import com.medlearn.shared.DomainError;
import com.medlearn.shared.LanguageTag;
import com.medlearn.shared.Script;
import com.medlearn.shared.port.FontProviderPort;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Renders study guides to PDF.
 *
 * <p>Separate from the chapter renderers because this is a different document: it is read
 * for revision rather than reference, so it is laid out for scanning - short sections,
 * generous spacing, and self-test answers visually separated from their questions so the
 * reader can cover them.
 */
@Component
public class StudyGuidePdfRenderer implements StudyGuideRendererPort {

    private final FontProviderPort fonts;

    public StudyGuidePdfRenderer(FontProviderPort fonts) {
        this.fonts = fonts;
    }

    @Override
    public Rendered render(List<StudyGuide> guides, LanguageTag language, String documentTitle) {
        Path regular = fonts.regularFont(language)
                .orElseThrow(() -> new com.medlearn.shared.FontUnavailableException(
                        new DomainError.FontUnavailable(language, fonts.searchLocationsDescription())));
        Path bold = fonts.boldFont(language).orElse(regular);

        String html = buildHtml(guides, language, documentTitle);

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.useFont(regular.toFile(), "GuideBody");
            builder.useFont(bold.toFile(), "GuideHeading");
            builder.withHtmlContent(html, null);
            builder.toStream(out);
            builder.run();

            byte[] pdf = out.toByteArray();
            return new Rendered(pdf, countPages(pdf));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not render study guide", e);
        }
    }

    private static int countPages(byte[] pdf) {
        try (var document = org.apache.pdfbox.Loader.loadPDF(pdf)) {
            return document.getNumberOfPages();
        } catch (IOException e) {
            return 0;
        }
    }

    private String buildHtml(List<StudyGuide> guides, LanguageTag language, String documentTitle) {
        StringBuilder html = new StringBuilder(8192);
        // XHTML, not HTML: OpenHTMLtoPDF parses with a SAX parser and an unclosed void
        // element fails the whole render rather than degrading.
        html.append("<!DOCTYPE html><html lang=\"").append(escape(language.tag()))
                .append("\"><head><meta charset=\"utf-8\"/><title>")
                .append(escape(documentTitle))
                .append("</title><style>").append(css(language.script())).append("</style></head><body>");

        html.append("<h1>").append(escape(documentTitle)).append("</h1>");

        for (StudyGuide guide : guides) {
            html.append(guides.indexOf(guide) == 0 ? "<div class=\"chapter first\">" : "<div class=\"chapter\">");
            html.append("<h2>").append(escape(guide.chapterTitle())).append("</h2>");

            bulletSection(html, "Learning objectives", guide.learningObjectives());

            if (!guide.keyConcepts().isEmpty()) {
                html.append("<h3>Key concepts</h3><table>");
                for (StudyGuide.Concept concept : guide.keyConcepts()) {
                    html.append("<tr><td class=\"term\">").append(escape(concept.term()))
                            .append("</td><td>").append(escape(concept.definition()))
                            .append("<div class=\"why\">").append(escape(concept.whyItMatters()))
                            .append("</div></td></tr>");
                }
                html.append("</table>");
            }

            bulletSection(html, "Mechanisms", guide.mechanisms());
            bulletSection(html, "Clinical pearls", guide.clinicalPearls());
            calloutSection(html, "Red flags", guide.redFlags());
            bulletSection(html, "High-yield facts", guide.highYieldFacts());
            bulletSection(html, "Mnemonics", guide.mnemonics());

            if (!guide.selfTest().isEmpty()) {
                html.append("<h3>Self-test</h3>");
                int number = 1;
                for (StudyGuide.SelfTestItem item : guide.selfTest()) {
                    html.append("<div class=\"q\"><div class=\"question\">")
                            .append(number++).append(". ").append(escape(item.question()))
                            .append("</div><div class=\"answer\">")
                            .append(escape(item.answer()));
                    if (item.explanation() != null && !item.explanation().isBlank()) {
                        html.append("<div class=\"why\">").append(escape(item.explanation()))
                                .append("</div>");
                    }
                    html.append("</div></div>");
                }
            }
            html.append("</div>");
        }

        return html.append("</body></html>").toString();
    }

    private static void bulletSection(StringBuilder html, String title, List<String> items) {
        if (items.isEmpty()) return;
        html.append("<h3>").append(title).append("</h3><ul>");
        items.forEach(item -> html.append("<li>").append(escape(item)).append("</li>"));
        html.append("</ul>");
    }

    private static void calloutSection(StringBuilder html, String title, List<String> items) {
        if (items.isEmpty()) return;
        html.append("<h3>").append(title).append("</h3><div class=\"callout\"><ul>");
        items.forEach(item -> html.append("<li>").append(escape(item)).append("</li>"));
        html.append("</ul></div>");
    }

    private static String css(Script script) {
        String breaking = script == Script.CJK
                ? "word-break: break-all;"
                : "word-break: normal;";

        return """
                @page { size: A4; margin: 2cm 1.8cm; }
                body { font-family: "GuideBody", sans-serif; font-size: 10.5pt;
                       line-height: 1.5; color: #15181d; %s }
                h1 { font-family: "GuideHeading", sans-serif; font-size: 19pt; margin: 0 0 16pt 0; }
                h2 { font-family: "GuideHeading", sans-serif; font-size: 15pt;
                     margin: 0 0 10pt 0; padding-bottom: 5pt; border-bottom: 1.5pt solid #2b6cb0;
                     color: #2b6cb0; }
                h3 { font-family: "GuideHeading", sans-serif; font-size: 11.5pt;
                     margin: 14pt 0 5pt 0; color: #2d3748; page-break-after: avoid; }
                .chapter { page-break-before: always; }
                /* Explicit class, not :first-of-type: this CSS engine ignores that selector,
                   which left the document title alone on page one. */
                .chapter.first { page-break-before: avoid; }
                ul { margin: 0 0 6pt 0; padding-left: 15pt; }
                li { margin-bottom: 4pt; }
                table { width: 100%%; border-collapse: collapse; margin-bottom: 6pt; }
                td { vertical-align: top; padding: 4pt 5pt; border-bottom: 0.5pt solid #e2e8f0; }
                td.term { width: 28%%; font-family: "GuideHeading", sans-serif; color: #1a365d; }
                .why { font-size: 9pt; color: #4a5568; margin-top: 2pt; }
                .callout { border-left: 3pt solid #c53030; background: #fff5f5;
                           padding: 6pt 8pt; margin-bottom: 6pt; }
                /* Questions and answers are kept visually distinct so the reader can cover
                   the answer while revising - the whole point of a self-test. */
                .q { margin-bottom: 8pt; page-break-inside: avoid; }
                .question { font-family: "GuideHeading", sans-serif; }
                .answer { margin-top: 3pt; padding-left: 10pt; border-left: 2pt solid #cbd5e0;
                          color: #2d3748; }
                """.formatted(breaking);
    }

    private static String escape(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }
}
