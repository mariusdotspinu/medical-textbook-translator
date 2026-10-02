package com.medlearn.learning.application;

import com.medlearn.learning.domain.port.StudyGuideRendererPort;
import com.medlearn.learning.domain.StudyGuide;
import com.medlearn.learning.domain.port.StudyGuideModelPort;
import com.medlearn.shared.LanguageTag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds the study guide for a chapter range and renders it.
 *
 * <p>One request per chapter rather than one for the whole range: a model given eight
 * chapters at once produces eight shallow summaries, because the useful detail does not fit.
 * Per chapter it can be specific, and the cost is still one request per 40 pages.
 */
@Service
public class GenerateStudyGuideUseCase {

    private static final Logger log = LoggerFactory.getLogger(GenerateStudyGuideUseCase.class);

    private final List<StudyGuideModelPort> models;
    private final StudyGuideRendererPort renderer;

    public GenerateStudyGuideUseCase(
            List<StudyGuideModelPort> models, StudyGuideRendererPort renderer) {
        this.models = models;
        this.renderer = renderer;
    }

    /**
     * @param chapters      translated text per chapter, in reading order
     * @param dryRun        use the free, non-calling generator
     * @return the rendered PDF
     */
    public Rendered generate(
            List<ChapterText> chapters,
            LanguageTag targetLanguage,
            Map<String, String> glossary,
            String documentTitle,
            boolean dryRun) {

        StudyGuideModelPort model = selectModel(dryRun);
        List<StudyGuide> guides = new ArrayList<>(chapters.size());

        for (ChapterText chapter : chapters) {
            if (chapter.text().isBlank()) continue;
            guides.add(model.generate(new StudyGuideModelPort.Request(
                    chapter.title(), chapter.text(), targetLanguage, glossary)));
        }

        if (guides.isEmpty()) {
            throw new IllegalStateException("No chapter had enough text for a study guide");
        }

        StudyGuideRendererPort.Rendered rendered =
                renderer.render(guides, targetLanguage, documentTitle);
        log.info("Study guide generated for {} chapter(s): {} pages, {} KB",
                guides.size(), rendered.pageCount(), rendered.pdf().length / 1024);
        return new Rendered(rendered.pdf(), guides.size(), rendered.pageCount());
    }

    private StudyGuideModelPort selectModel(boolean dryRun) {
        return models.stream()
                .filter(model -> model.billable() != dryRun)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(dryRun
                        ? "no dry-run study-guide generator is configured"
                        : "no live study-guide generator - set ANTHROPIC_API_KEY, or use dryRun"));
    }

    public record ChapterText(String title, String text) {}

    public record Rendered(byte[] pdf, int chapterCount, int pageCount) {}
}
