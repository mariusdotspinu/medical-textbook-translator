package com.medlearn.library.domain;

import com.medlearn.shared.BlobRef;
import com.medlearn.shared.ContentHash;
import com.medlearn.shared.LanguageTag;
import com.medlearn.shared.TextbookId;

import java.time.Instant;
import java.util.Optional;

/**
 * An ingested textbook.
 *
 * <p>{@code Optional} components rather than nullable ones: the source language is unknown
 * until detected, and {@code ingestedAt} only exists once extraction finished. Making that
 * explicit in the type stops a null leaking into a prompt or a response body.
 *
 * @param hash     content hash of the original file; the uniqueness key that makes a
 *                 re-upload a no-op
 * @param pageCount pages as reported by the document catalog
 */
public record Textbook(
        TextbookId id,
        String title,
        Optional<String> authors,
        Optional<LanguageTag> sourceLanguage,
        int pageCount,
        long byteSize,
        ContentHash hash,
        BlobRef blob,
        TextbookStatus status,
        ChapterSource chapterSource,
        Instant uploadedAt,
        Optional<Instant> ingestedAt) {

    public Textbook {
        if (id == null) throw new IllegalArgumentException("textbook id is required");
        if (title == null || title.isBlank()) throw new IllegalArgumentException("title is required");
        if (hash == null) throw new IllegalArgumentException("content hash is required");
        if (blob == null) throw new IllegalArgumentException("blob ref is required");
        if (pageCount < 0) throw new IllegalArgumentException("pageCount cannot be negative");
        if (byteSize < 0) throw new IllegalArgumentException("byteSize cannot be negative");
        if (status == null) status = TextbookStatus.UPLOADED;
        if (chapterSource == null) chapterSource = ChapterSource.NONE;
        if (uploadedAt == null) uploadedAt = Instant.now();
        authors = authors == null ? Optional.empty() : authors;
        sourceLanguage = sourceLanguage == null ? Optional.empty() : sourceLanguage;
        ingestedAt = ingestedAt == null ? Optional.empty() : ingestedAt;
    }

    /** Freshly uploaded, before any extraction. */
    public static Textbook justUploaded(
            TextbookId id,
            String title,
            Optional<String> authors,
            int pageCount,
            long byteSize,
            ContentHash hash,
            BlobRef blob) {
        return new Textbook(
                id, title, authors, Optional.empty(), pageCount, byteSize, hash, blob,
                TextbookStatus.UPLOADED, ChapterSource.NONE, Instant.now(), Optional.empty());
    }

    public boolean readyForTranslation() {
        return status == TextbookStatus.READY;
    }

    public Textbook withStatus(TextbookStatus newStatus) {
        return new Textbook(id, title, authors, sourceLanguage, pageCount, byteSize, hash, blob,
                newStatus, chapterSource, uploadedAt, ingestedAt);
    }
}
