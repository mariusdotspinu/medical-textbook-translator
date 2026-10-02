package com.medlearn.translation.domain.port;

import com.medlearn.shared.LanguageTag;
import com.medlearn.shared.PageRange;
import com.medlearn.shared.TextbookId;
import com.medlearn.translation.domain.GlossaryTerm;
import com.medlearn.translation.domain.GlossaryTermId;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** A book's glossary per target language, and which pages have been scanned for terms. */
public interface GlossaryRepository {

    List<GlossaryTerm> findAll(TextbookId textbookId, LanguageTag language);

    Optional<GlossaryTerm> findBySource(TextbookId textbookId, LanguageTag language, String sourceTerm);

    /**
     * Inserts terms whose source term is new, ignoring case. A term already present, pinned
     * or not, is left exactly as it is: the glossary only grows automatically, it never
     * changes a rendering that pages may already use.
     */
    void addNew(TextbookId textbookId, List<GlossaryTerm> terms);

    /** Inserts or replaces a term by its source term; used for human edits, which pin. */
    GlossaryTerm save(TextbookId textbookId, GlossaryTerm term);

    void addOccurrences(TextbookId textbookId, LanguageTag language, Map<String, Integer> bySourceTerm);

    boolean delete(TextbookId textbookId, LanguageTag language, GlossaryTermId id);

    Set<Integer> coveredPages(TextbookId textbookId, LanguageTag language, PageRange range);

    void markCovered(TextbookId textbookId, LanguageTag language, Collection<Integer> pages);
}
