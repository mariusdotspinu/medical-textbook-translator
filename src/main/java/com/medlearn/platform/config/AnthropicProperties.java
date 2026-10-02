package com.medlearn.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param apiKey      normally supplied by the {@code ANTHROPIC_API_KEY} environment
 *                    variable. When empty, live translation is simply unavailable and the
 *                    application still starts - dry runs do not need it.
 * @param model       model id for the translation and study-guide routes
 *
 * <p>Concurrency is not configured here: {@code medlearn.translation.max-in-flight} caps
 * translation requests across all jobs. Study guides make one request per chapter, in
 * sequence, so they need no limit of their own.
 */
@ConfigurationProperties(prefix = "medlearn.anthropic")
public record AnthropicProperties(String apiKey, String model) {

    public AnthropicProperties {
        if (apiKey == null) apiKey = "";
        if (model == null || model.isBlank()) model = "claude-opus-5";
    }

    public boolean configured() {
        return !apiKey.isBlank();
    }
}
