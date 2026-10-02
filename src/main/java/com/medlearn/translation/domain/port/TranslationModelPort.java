package com.medlearn.translation.domain.port;

import com.medlearn.shared.LanguageTag;
import com.medlearn.translation.domain.GlossaryTerm;
import com.medlearn.translation.domain.QualityFinding;
import com.medlearn.translation.domain.TranslatedBlock;

import java.util.List;

/**
 * Translates a batch of blocks.
 *
 * <p>Batching rather than per-block calls is deliberate: a 40-page chapter is around a
 * thousand blocks, and a request each would be slow, expensive, and would throw away the
 * shared prompt prefix that prompt caching depends on.
 *
 * <p>The glossary and chapter context travel with every batch because they form the
 * <em>stable prefix</em> of the prompt. Keeping them identical across a chapter's batches is
 * what lets the cache serve them instead of re-reading them each time.
 */
public interface TranslationModelPort {

    /** Identifies the backing implementation in logs and job results. */
    String name();

    /** Whether this implementation actually calls a paid API. */
    boolean billable();

    Result translate(Batch batch);

    /**
     * @param glossary       the book's agreed terminology; keeps "ventricle" identical on
     *                       page 40 and page 940
     * @param styleGuide     stable per-book instructions
     * @param chapterContext short synopsis giving the model the chapter's subject matter
     */
    record Batch(
            List<SourceBlock> blocks,
            LanguageTag targetLanguage,
            List<GlossaryTerm> glossary,
            String styleGuide,
            String chapterContext) {

        public Batch {
            if (blocks == null || blocks.isEmpty()) {
                throw new IllegalArgumentException("nothing to translate");
            }
            blocks = List.copyOf(blocks);
            glossary = glossary == null ? List.of() : List.copyOf(glossary);
            styleGuide = styleGuide == null ? "" : styleGuide;
            chapterContext = chapterContext == null ? "" : chapterContext;
        }

        public int totalCharacters() {
            return blocks.stream().mapToInt(block -> block.text().length()).sum();
        }
    }

    record SourceBlock(String id, String text) {}

    /**
     * @param findings problems detected while translating - a refusal, a dropped block.
     *                 Returned rather than thrown so one bad block cannot fail a chapter.
     */
    record Result(List<TranslatedBlock> blocks, List<QualityFinding> findings, Usage usage) {
        public Result {
            blocks = blocks == null ? List.of() : List.copyOf(blocks);
            findings = findings == null ? List.of() : List.copyOf(findings);
            usage = usage == null ? Usage.none() : usage;
        }
    }

    /**
     * Token accounting.
     *
     * <p>{@code cacheReadTokens} is the one to watch: if it stays zero across a chapter's
     * batches, prompt caching has silently regressed and the chapter costs several times
     * what it should, with no error anywhere.
     */
    record Usage(long inputTokens, long outputTokens, long cacheReadTokens, long cacheWriteTokens) {

        public static Usage none() {
            return new Usage(0, 0, 0, 0);
        }

        public Usage plus(Usage other) {
            return new Usage(
                    inputTokens + other.inputTokens,
                    outputTokens + other.outputTokens,
                    cacheReadTokens + other.cacheReadTokens,
                    cacheWriteTokens + other.cacheWriteTokens);
        }

        /** Total prompt size is the sum of all three input figures, not {@code inputTokens}. */
        public long totalInputTokens() {
            return inputTokens + cacheReadTokens + cacheWriteTokens;
        }
    }
}
