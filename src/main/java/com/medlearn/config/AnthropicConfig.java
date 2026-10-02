package com.medlearn.config;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.medlearn.learning.adapter.ai.AnthropicStudyGuideAdapter;
import com.medlearn.platform.config.AnthropicProperties;
import com.medlearn.translation.adapter.ai.AnthropicTermExtractionAdapter;
import com.medlearn.translation.adapter.ai.AnthropicTranslationAdapter;
import com.medlearn.translation.application.PromptAssembler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Wires live translation, but only when an API key is actually present.
 *
 * <p>Conditional on purpose: the application has to start and be fully usable without a key,
 * because the dry-run path exists precisely so that extraction and rendering can be
 * developed and debugged without spending anything. Requiring a key to boot would make the
 * free path unreachable.
 *
 * <p>With no key configured, {@code TranslateChaptersUseCase} finds no billable translator
 * and says so plainly when a non-dry-run translation is requested.
 */
@Configuration
@ConditionalOnExpression("!'${medlearn.anthropic.api-key:}'.isEmpty()")
public class AnthropicConfig {

    private static final Logger log = LoggerFactory.getLogger(AnthropicConfig.class);

    /**
     * Generous per-request timeout. A batch of forty medical paragraphs at high output
     * length legitimately takes a while, and the SDK's default can cut it off mid-response -
     * which surfaces later as a truncated translation rather than as a timeout.
     */
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(10);

    @Bean
    public AnthropicClient anthropicClient(AnthropicProperties properties) {
        log.info("Live translation enabled with model {}", properties.model());
        return AnthropicOkHttpClient.builder()
                .apiKey(properties.apiKey())
                .timeout(REQUEST_TIMEOUT)
                .build();
    }

    @Bean
    public AnthropicTranslationAdapter anthropicTranslationAdapter(
            AnthropicClient client, PromptAssembler prompts, AnthropicProperties properties) {
        return new AnthropicTranslationAdapter(client, prompts, properties);
    }

    @Bean
    public AnthropicTermExtractionAdapter anthropicTermExtractionAdapter(
            AnthropicClient client, AnthropicProperties properties) {
        return new AnthropicTermExtractionAdapter(client, properties);
    }

    @Bean
    public AnthropicStudyGuideAdapter anthropicStudyGuideAdapter(
            AnthropicClient client, AnthropicProperties properties) {
        return new AnthropicStudyGuideAdapter(client, properties);
    }
}
