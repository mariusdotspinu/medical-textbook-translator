package com.medlearn.shared;

/**
 * Expected, non-exceptional failures that the domain reports rather than throws.
 *
 * <p>Sealed so that every place handling an error must switch exhaustively; adding a
 * new failure mode becomes a compile error at each handling site instead of a silently
 * unhandled case at runtime. Genuine bugs still throw.
 */
public sealed interface DomainError {

    /** Human-readable, safe to show in the UI. */
    String message();

    record TextbookNotFound(TextbookId id) implements DomainError {
        @Override
        public String message() {
            return "No textbook with id " + id;
        }
    }

    record ChapterNotFound(ChapterId id) implements DomainError {
        @Override
        public String message() {
            return "No chapter with id " + id;
        }
    }

    record JobNotFound(JobId id) implements DomainError {
        @Override
        public String message() {
            return "No job with id " + id;
        }
    }

    /** The same file was already ingested; the caller should use the existing textbook. */
    record DuplicateUpload(TextbookId existing, ContentHash hash) implements DomainError {
        @Override
        public String message() {
            return "This file was already uploaded as textbook " + existing;
        }
    }

    record NotAPdf(String detail) implements DomainError {
        @Override
        public String message() {
            return "Uploaded file is not a readable PDF: " + detail;
        }
    }

    record EncryptedPdf(TextbookId id) implements DomainError {
        @Override
        public String message() {
            return "PDF is encrypted and cannot be extracted without a password";
        }
    }

    record TextbookNotReady(TextbookId id, String status) implements DomainError {
        @Override
        public String message() {
            return "Textbook %s is not ready yet (status: %s)".formatted(id, status);
        }
    }

    /** A job for the textbook is still running, so it cannot be deleted yet. */
    record TextbookBusy(TextbookId id) implements DomainError {
        @Override
        public String message() {
            return "Textbook %s still has a running job; cancel it or wait for it to finish"
                    .formatted(id);
        }
    }

    /** A requested target language has no embeddable font available on this installation. */
    record FontUnavailable(LanguageTag language, String searchedPath) implements DomainError {
        @Override
        public String message() {
            return "No font covering %s (%s) found in %s - translated text would render as blank boxes"
                    .formatted(language, language.script(), searchedPath);
        }
    }
}
