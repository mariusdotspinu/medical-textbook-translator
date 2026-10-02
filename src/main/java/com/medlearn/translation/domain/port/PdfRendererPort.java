package com.medlearn.translation.domain.port;

import com.medlearn.ingestion.domain.PageLayout;
import com.medlearn.shared.LanguageTag;
import com.medlearn.translation.domain.QualityFinding;
import com.medlearn.translation.domain.RenderMode;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Turns translated text back into a PDF. */
public interface PdfRendererPort {

    RenderMode mode();

    RenderResult render(RenderRequest request);

    /**
     * @param sourcePdf    the original document, needed by the overlay renderer for figures
     * @param pages        pages to render, in order
     * @param translations block id to translated text; a block absent here keeps its source
     *                     text, which is how refused and quarantined blocks are handled
     */
    record RenderRequest(
            Path sourcePdf,
            List<PageLayout> pages,
            Map<String, String> translations,
            LanguageTag targetLanguage,
            String documentTitle) {

        public RenderRequest {
            if (pages == null || pages.isEmpty()) {
                throw new IllegalArgumentException("nothing to render");
            }
            translations = translations == null ? Map.of() : Map.copyOf(translations);
        }

        /** Translated text when there is one, otherwise the untranslated source. */
        public String textFor(com.medlearn.ingestion.domain.TextBlock block) {
            return translations.getOrDefault(block.id(), block.text());
        }
    }

    /**
     * @param findings rendering problems, chiefly overflow - reported rather than thrown so
     *                 a mostly-good chapter is still delivered
     */
    record RenderResult(byte[] pdf, int pageCount, List<QualityFinding> findings) {
        public RenderResult {
            findings = findings == null ? List.of() : List.copyOf(findings);
        }
    }
}
