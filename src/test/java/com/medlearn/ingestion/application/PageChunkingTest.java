package com.medlearn.ingestion.application;

import com.medlearn.shared.PageRange;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Page chunking must partition the book exactly: a gap means silently untranslated pages,
 * an overlap means paying twice to extract the same page.
 */
class PageChunkingTest {

    @ParameterizedTest(name = "{0} pages across {1} workers covers every page exactly once")
    @CsvSource({
            "1, 1", "1, 8", "7, 3", "8, 8", "100, 8", "1000, 8", "1013, 7", "999, 32", "5, 64"
    })
    void partitionsEveryPageExactlyOnce(int pageCount, int workers) {
        List<PageRange> chunks = IngestTextbookUseCase.splitIntoChunks(pageCount, workers);

        assertThat(chunks).isNotEmpty();
        assertThat(chunks.getFirst().startPage()).isEqualTo(1);
        assertThat(chunks.getLast().endPage()).isEqualTo(pageCount);
        assertThat(chunks.stream().mapToInt(PageRange::pageCount).sum()).isEqualTo(pageCount);

        // Contiguous with no gap and no overlap.
        for (int i = 1; i < chunks.size(); i++) {
            assertThat(chunks.get(i).startPage())
                    .as("chunk %d must start right after chunk %d ends", i, i - 1)
                    .isEqualTo(chunks.get(i - 1).endPage() + 1);
        }
    }

    @Test
    @DisplayName("chunk sizes differ by at most one page")
    void balancesChunkSizes() {
        List<PageRange> chunks = IngestTextbookUseCase.splitIntoChunks(1013, 8);

        int smallest = chunks.stream().mapToInt(PageRange::pageCount).min().orElseThrow();
        int largest = chunks.stream().mapToInt(PageRange::pageCount).max().orElseThrow();
        assertThat(largest - smallest).isLessThanOrEqualTo(1);
    }

    @Test
    @DisplayName("never creates more chunks than there are pages")
    void doesNotCreateEmptyChunks() {
        // Asking for 64 workers on a 5-page document must not produce 59 empty ranges,
        // which PageRange would reject anyway.
        assertThat(IngestTextbookUseCase.splitIntoChunks(5, 64)).hasSize(5);
    }

    @Test
    void handlesEmptyDocument() {
        assertThat(IngestTextbookUseCase.splitIntoChunks(0, 8)).isEmpty();
    }
}
