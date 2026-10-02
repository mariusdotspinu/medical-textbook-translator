package com.medlearn.library.domain.port;

import com.medlearn.library.domain.ChapterSource;
import com.medlearn.library.domain.Textbook;
import com.medlearn.library.domain.TextbookStatus;
import com.medlearn.shared.ContentHash;
import com.medlearn.shared.LanguageTag;
import com.medlearn.shared.TextbookId;

import java.util.List;
import java.util.Optional;

public interface TextbookRepository {

    void insert(Textbook textbook);

    Optional<Textbook> findById(TextbookId id);

    /** Duplicate detection: the same bytes must not be ingested twice. */
    Optional<Textbook> findByHash(ContentHash hash);

    List<Textbook> findAll();

    void updateStatus(TextbookId id, TextbookStatus status);

    /** Called once extraction completes, recording how chapters were found. */
    void markReady(TextbookId id, ChapterSource source, LanguageTag detectedLanguage, int pageCount);

    /**
     * Fails every textbook whose ingestion never finished ({@code UPLOADED} or
     * {@code EXTRACTING}).
     *
     * @return how many textbooks were failed
     */
    int failUnfinishedIngestion();

    void delete(TextbookId id);
}
