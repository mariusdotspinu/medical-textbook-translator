package com.medlearn.ingestion.adapter.pdf;

/**
 * Unexpected failure reading a PDF.
 *
 * <p>A genuine bug or broken file, not a modelled outcome - cases the user can act on
 * (not a PDF, password protected) are reported as {@code DomainError} instead.
 */
public class PdfAccessException extends RuntimeException {
    public PdfAccessException(String message, Throwable cause) {
        super(message, cause);
    }

    public PdfAccessException(String message) {
        super(message);
    }
}
