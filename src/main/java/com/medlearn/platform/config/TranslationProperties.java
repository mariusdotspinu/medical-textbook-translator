package com.medlearn.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param maxCharsPerBatch source characters per request
 * @param maxInFlight      concurrent translation requests across all jobs together; exists to
 *                         respect the account's API rate limit, which is the real throughput
 *                         ceiling - virtual threads do not raise it
 * @param lengthRatioMin   below this, a translation is suspected of being truncated
 * @param lengthRatioMax   above this, of being padded or hallucinated
 */
@ConfigurationProperties(prefix = "medlearn.translation")
public record TranslationProperties(
        int maxCharsPerBatch, int maxInFlight, double lengthRatioMin, double lengthRatioMax) {

    public TranslationProperties {
        if (maxCharsPerBatch <= 0) maxCharsPerBatch = 24_000;
        if (maxInFlight <= 0) maxInFlight = 8;
        if (lengthRatioMin <= 0) lengthRatioMin = 0.4;
        if (lengthRatioMax <= 0) lengthRatioMax = 2.5;
    }
}
