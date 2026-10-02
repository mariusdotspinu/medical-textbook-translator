package com.medlearn.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param extractionParallelism workers for PDF extraction; {@code 0} means auto.
 *                              Each worker holds its own {@code PDDocument} (PDFBox is not
 *                              thread-safe), so this bounds peak memory as much as CPU.
 * @param pageWriteBatchSize    pages accumulated before a batched insert
 */
@ConfigurationProperties(prefix = "medlearn.ingestion")
public record IngestionProperties(int extractionParallelism, int pageWriteBatchSize) {

    /** Beyond ~8 concurrent PDDocument handles the gain flattens while memory keeps climbing. */
    private static final int AUTO_PARALLELISM_CEILING = 8;

    public IngestionProperties {
        if (extractionParallelism < 0) {
            throw new IllegalArgumentException(
                    "extraction-parallelism cannot be negative: " + extractionParallelism);
        }
        if (pageWriteBatchSize <= 0) pageWriteBatchSize = 100;
    }

    public int resolvedParallelism() {
        if (extractionParallelism > 0) return extractionParallelism;
        return Math.min(Runtime.getRuntime().availableProcessors(), AUTO_PARALLELISM_CEILING);
    }
}
