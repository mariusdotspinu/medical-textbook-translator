package com.medlearn.shared;

/**
 * No installed font covers the requested language.
 *
 * <p>Thrown rather than rendered around, because the alternative is silent: a PDF drawn with
 * a font missing the glyphs produces a page of blank boxes, raises nothing, and is only
 * discovered by a human opening the file.
 */
public class FontUnavailableException extends RuntimeException {

    private final transient DomainError.FontUnavailable error;

    public FontUnavailableException(DomainError.FontUnavailable error) {
        super(error.message());
        this.error = error;
    }

    public DomainError.FontUnavailable error() {
        return error;
    }
}
