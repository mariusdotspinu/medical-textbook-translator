package com.medlearn.translation.adapter.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonSchemaLocalValidation;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.StructuredOutputConfig;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.medlearn.platform.config.AnthropicProperties;
import com.medlearn.translation.application.PromptAssembler;
import com.medlearn.translation.domain.QualityFinding;
import com.medlearn.translation.domain.TranslatedBlock;
import com.medlearn.translation.domain.port.TranslationModelPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Translates through the Anthropic API.
 *
 * <p>Three decisions here carry most of the weight.
 *
 * <p><b>Structured output keyed by block id.</b> The response is a typed record, not prose
 * to be parsed. That is what makes a dropped, merged or reordered block detectable: a model
 * that quietly returns 19 translations for 20 blocks would otherwise produce a plausible PDF
 * with a paragraph missing and nothing to catch it.
 *
 * <p><b>A cached prompt prefix.</b> Instructions, style guide and glossary are sent as
 * system blocks with a cache breakpoint on the last one, so a chapter's many batches read
 * that prefix instead of re-paying for it. {@code cacheReadInputTokens} is logged for
 * exactly this reason: if it stays zero, caching has silently regressed and the chapter
 * costs several times what it should, with no error anywhere.
 *
 * <p><b>Refusals are handled, not thrown.</b> Medical content - toxicology, controlled
 * substances, pathogens - can trip safety classifiers, which arrives as HTTP 200 with
 * {@code stop_reason: refusal} rather than an exception. A refused batch is split and
 * retried per block so one paragraph cannot fail a 40-page chapter; blocks that are still
 * refused are reported and left in the source language.
 */
public class AnthropicTranslationAdapter implements TranslationModelPort {

    private static final Logger log = LoggerFactory.getLogger(AnthropicTranslationAdapter.class);

    /**
     * Generous because translations run longer than their source and the response carries
     * every block of the batch. Too low truncates mid-paragraph, which the quality gate then
     * reports as an implausible length - a confusing way to discover a configuration problem.
     */
    private static final long MAX_TOKENS = 32_000;

    /**
     * Bulk translation is mechanical, high-volume work: low effort is the right setting and
     * materially cheaper. Effort is pinned per route rather than varied per request, because
     * changing it mid-conversation invalidates the cache.
     */
    private static final OutputConfig.Effort TRANSLATION_EFFORT = OutputConfig.Effort.LOW;

    private final AnthropicClient client;
    private final PromptAssembler prompts;
    private final AnthropicProperties properties;

    public AnthropicTranslationAdapter(
            AnthropicClient client, PromptAssembler prompts, AnthropicProperties properties) {
        this.client = client;
        this.prompts = prompts;
        this.properties = properties;
    }

    @Override
    public String name() {
        return properties.model();
    }

    @Override
    public boolean billable() {
        return true;
    }

    @Override
    public Result translate(Batch batch) {
        try {
            return attempt(batch);
        } catch (RefusedException refusal) {
            return splitAndRetry(batch, refusal);
        }
    }

    private Result attempt(Batch batch) {
        StructuredMessageCreateParams<TranslationResponse> params = buildParams(batch);

        StructuredMessage<TranslationResponse> response;
        try {
            response = client.messages().create(params);
        } catch (RateLimitException e) {
            // The SDK already retries with backoff; reaching here means the limit is
            // sustained, so surfacing it is more useful than retrying forever.
            throw new IllegalStateException(
                    "Anthropic rate limit reached - lower medlearn.translation.max-in-flight", e);
        } catch (AnthropicServiceException e) {
            throw new IllegalStateException("Anthropic request failed: " + e.getMessage(), e);
        }

        // Checked before reading content: a refusal is a successful HTTP response whose
        // content is not a translation.
        if (response.stopReason().filter(StopReason.REFUSAL::equals).isPresent()) {
            throw new RefusedException(describeRefusal(response));
        }

        List<TranslatedBlock> translated = new ArrayList<>(batch.blocks().size());
        for (var block : response.content()) {
            block.text().ifPresent(typed -> {
                TranslationResponse payload = typed.text();
                if (payload == null || payload.translations() == null) return;
                for (TranslationItem item : payload.translations()) {
                    if (item != null && item.id() != null && item.text() != null) {
                        translated.add(new TranslatedBlock(item.id(), item.text()));
                    }
                }
            });
        }

        com.anthropic.models.messages.Usage usage = response.usage();
        logUsage(batch, usage);
        return new Result(translated, List.of(), toUsage(usage));
    }

    private StructuredMessageCreateParams<TranslationResponse> buildParams(Batch batch) {
        // System blocks ordered by how often they change, with the cache breakpoint at the
        // end of the shared portion - never after the per-request blocks.
        List<TextBlockParam> system = List.of(
                TextBlockParam.builder().text(prompts.instructions()).build(),
                TextBlockParam.builder()
                        .text(prompts.bookAndChapterContext(
                                batch.targetLanguage(), batch.styleGuide(), batch.chapterContext()))
                        .build(),
                TextBlockParam.builder()
                        .text(prompts.glossaryBlock(batch.glossary()))
                        // Five-minute TTL, not one hour: batches within a chapter start well
                        // under five minutes apart, so each read refreshes the entry for
                        // free, and the one-hour TTL's doubled write price would buy nothing.
                        .cacheControl(CacheControlEphemeral.builder().build())
                        .build());

        return MessageCreateParams.builder()
                .model(properties.model())
                .maxTokens(MAX_TOKENS)
                .thinking(ThinkingConfigAdaptive.builder().build())
                .systemOfTextBlockParams(system)
                .addUserMessage(prompts.renderBatch(batch.blocks()))
                .outputConfig(StructuredOutputConfig.<TranslationResponse>builder()
                        .effort(TRANSLATION_EFFORT)
                        .format(TranslationResponse.class, JsonSchemaLocalValidation.NO)
                        .build())
                .build();
    }

    /**
     * Splits a refused batch and retries each block alone.
     *
     * <p>A refusal usually concerns one passage, not the forty around it. Retrying per block
     * keeps the rest of the chapter translatable; the blocks that are still refused are
     * reported and left in the source language rather than silently dropped from the page.
     */
    private Result splitAndRetry(Batch batch, RefusedException refusal) {
        if (batch.blocks().size() == 1) {
            String blockId = batch.blocks().getFirst().id();
            log.warn("Block {} was refused ({}); leaving it untranslated", blockId, refusal.category);
            return new Result(
                    List.of(),
                    List.of(new QualityFinding.Refused(blockId, refusal.category)),
                    Usage.none());
        }

        log.info("Batch of {} refused ({}); retrying block by block",
                batch.blocks().size(), refusal.category);

        List<TranslatedBlock> translated = new ArrayList<>();
        List<QualityFinding> findings = new ArrayList<>();
        Usage totalUsage = Usage.none();

        for (SourceBlock block : batch.blocks()) {
            Result single = translate(new Batch(
                    List.of(block), batch.targetLanguage(), batch.glossary(),
                    batch.styleGuide(), batch.chapterContext()));
            translated.addAll(single.blocks());
            findings.addAll(single.findings());
            totalUsage = totalUsage.plus(single.usage());
        }
        return new Result(translated, findings, totalUsage);
    }

    private void logUsage(Batch batch, com.anthropic.models.messages.Usage usage) {
        long cacheRead = usage.cacheReadInputTokens().orElse(0L);
        long cacheWrite = usage.cacheCreationInputTokens().orElse(0L);

        log.debug("Translated {} blocks ({} chars): in={} out={} cacheRead={} cacheWrite={}",
                batch.blocks().size(), batch.totalCharacters(),
                usage.inputTokens(), usage.outputTokens(), cacheRead, cacheWrite);

        // The only ground truth that caching works. A silent regression here costs several
        // times more per chapter and raises no error at all.
        if (cacheRead == 0 && cacheWrite == 0) {
            log.warn("No prompt cache activity on this request - the shared prefix may have "
                    + "become unstable, which multiplies the cost of every batch");
        }
    }

    private static Usage toUsage(com.anthropic.models.messages.Usage usage) {
        return new Usage(
                usage.inputTokens(),
                usage.outputTokens(),
                usage.cacheReadInputTokens().orElse(0L),
                usage.cacheCreationInputTokens().orElse(0L));
    }

    private static String describeRefusal(StructuredMessage<TranslationResponse> response) {
        return response.stopDetails()
                .map(details -> details.category().toString())
                .orElse("no category given");
    }

    /** Signals a safety refusal, which is a 200 response rather than an error. */
    private static final class RefusedException extends RuntimeException {
        private final transient String category;

        private RefusedException(String category) {
            super("model refused the request: " + category);
            this.category = category;
        }
    }

    // --- structured output shape -------------------------------------------

    /**
     * The response contract.
     *
     * <p>Ids are echoed back deliberately: matching on position instead would make a single
     * reordering corrupt an entire chapter invisibly.
     */
    public record TranslationResponse(
            @JsonPropertyDescription(
                    "One entry for every block in the request, in the same order.")
            List<TranslationItem> translations) {}

    public record TranslationItem(
            @JsonPropertyDescription(
                    "The block id exactly as given in the request, e.g. p412-b7.")
            String id,

            @JsonPropertyDescription(
                    "The translated text of this block, complete and with all numbers, "
                            + "units, drug names and figure references preserved verbatim.")
            String text) {}
}
