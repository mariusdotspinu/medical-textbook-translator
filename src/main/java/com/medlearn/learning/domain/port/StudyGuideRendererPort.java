package com.medlearn.learning.domain.port;

import com.medlearn.learning.domain.StudyGuide;
import com.medlearn.shared.LanguageTag;

import java.util.List;

/** Renders study guides to PDF. */
public interface StudyGuideRendererPort {

    Rendered render(List<StudyGuide> guides, LanguageTag targetLanguage, String documentTitle);

    record Rendered(byte[] pdf, int pageCount) {}
}
