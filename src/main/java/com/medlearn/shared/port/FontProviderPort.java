package com.medlearn.shared.port;

import com.medlearn.shared.LanguageTag;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Supplies a font that can actually render the target language.
 *
 * <p>This port exists because of a failure mode with no error message: a PDF drawn with a
 * font lacking the needed glyphs renders blank boxes, and nothing in the pipeline notices.
 * Resolution therefore fails loudly and early rather than producing an unreadable document.
 */
public interface FontProviderPort {

    /**
     * @return the font file for this language, or empty if none is installed - the caller
     *         should report {@code DomainError.FontUnavailable} rather than render
     */
    Optional<Path> regularFont(LanguageTag language);

    Optional<Path> boldFont(LanguageTag language);

    /** Where fonts were looked for, so the error can tell the user what to install and where. */
    String searchLocationsDescription();
}
