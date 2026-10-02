package com.medlearn.learning.adapter.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonSchemaLocalValidation;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredOutputConfig;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.medlearn.platform.config.AnthropicProperties;
import com.medlearn.learning.domain.StudyGuide;
import com.medlearn.learning.domain.port.StudyGuideModelPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Generates a study guide with the same model, at a different effort.
 *
 * <p>Effort is {@code HIGH} here where translation runs at {@code LOW}, and the difference
 * is deliberate: translating is mechanical transformation of text that already exists, while
 * deciding what a chapter's mechanisms, pearls and red flags actually are is synthesis. This
 * is one request per chapter rather than hundreds, so the higher setting costs little in
 * absolute terms.
 */
public class AnthropicStudyGuideAdapter implements StudyGuideModelPort {

    private static final Logger log = LoggerFactory.getLogger(AnthropicStudyGuideAdapter.class);

    private static final long MAX_TOKENS = 32_000;

    /**
     * Chapter text sent to the model, capped. A very long chapter would otherwise dominate
     * the request; the opening is where objectives, definitions and mechanisms live, which is
     * what a study guide is built from.
     */
    private static final int MAX_CHAPTER_CHARS = 120_000;

    /** Sorted, always: an unsorted glossary renders different bytes and breaks the cache. */
    private static String glossaryBlock(java.util.Map<String, String> glossary) {
        if (glossary.isEmpty()) return "GLOSSARY: none established for this book.\n";
        StringBuilder block = new StringBuilder("GLOSSARY - use these renderings exactly:\n");
        glossary.entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .forEach(entry -> block.append("  ").append(entry.getKey())
                        .append(" -> ").append(entry.getValue()).append('\n'));
        return block.toString();
    }

    private static final String INSTRUCTIONS = """
            You are preparing study material for physicians and residents from a chapter of a
            medical textbook that has already been translated into the reader's language.

            Write everything in the same language as the chapter text you are given. Do not
            translate it back, and do not mix languages except where a term is conventionally
            kept in Latin or English, in which case give the original in parentheses once.

            Accuracy rules, which override brevity:
            - Every figure, dose, cut-off and criterion must match the chapter exactly. Never
              round, convert, or restate a number from memory.
            - Never introduce clinical content that is not in the chapter, even if correct.
              The reader is revising this chapter, not a different source.
            - Where the chapter is silent on something, leave it out rather than filling it in.
            - Prefer the chapter's own terminology over a synonym.

            Write for someone who has read the chapter once and is revising it: reconstructable
            reasoning, not a list of conclusions.
            """;

    private final AnthropicClient client;
    private final AnthropicProperties properties;

    public AnthropicStudyGuideAdapter(AnthropicClient client, AnthropicProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public boolean billable() {
        return true;
    }

    @Override
    public StudyGuide generate(Request request) {
        String chapterText = request.translatedText().length() > MAX_CHAPTER_CHARS
                ? request.translatedText().substring(0, MAX_CHAPTER_CHARS)
                : request.translatedText();

        List<TextBlockParam> system = List.of(
                TextBlockParam.builder().text(INSTRUCTIONS).build(),
                TextBlockParam.builder()
                        .text(glossaryBlock(request.glossary()))
                        .cacheControl(CacheControlEphemeral.builder().build())
                        .build());

        var params = MessageCreateParams.builder()
                .model(properties.model())
                .maxTokens(MAX_TOKENS)
                .thinking(ThinkingConfigAdaptive.builder().build())
                .systemOfTextBlockParams(system)
                .addUserMessage("""
                        Chapter title: %s
                        Target language: %s

                        Chapter text follows.

                        %s
                        """.formatted(request.chapterTitle(),
                        request.targetLanguage().englishName(), chapterText))
                .outputConfig(StructuredOutputConfig.<StudyGuideSchema>builder()
                        // Synthesis, not transformation: worth the higher setting on a
                        // request that happens once per chapter.
                        .effort(OutputConfig.Effort.HIGH)
                        .format(StudyGuideSchema.class, JsonSchemaLocalValidation.NO)
                        .build())
                .build();

        StructuredMessage<StudyGuideSchema> response = client.messages().create(params);

        if (response.stopReason().filter(StopReason.REFUSAL::equals).isPresent()) {
            throw new IllegalStateException(
                    "The model declined to produce a study guide for this chapter");
        }

        return response.content().stream()
                .flatMap(block -> block.text().stream())
                .map(typed -> typed.text())
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .map(StudyGuideSchema::toDomain)
                .orElseThrow(() -> new IllegalStateException("No study guide returned"));
    }
}
