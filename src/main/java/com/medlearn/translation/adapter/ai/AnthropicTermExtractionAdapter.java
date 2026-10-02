package com.medlearn.translation.adapter.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonSchemaLocalValidation;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredOutputConfig;
import com.anthropic.models.messages.TextBlockParam;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.medlearn.platform.config.AnthropicProperties;
import com.medlearn.translation.domain.GlossaryTerm.TermKind;
import com.medlearn.translation.domain.port.TermExtractionPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * Proposes glossary terms through the Anthropic API.
 *
 * <p>Medium effort: choosing which terms matter and their established rendering is judgement,
 * not mechanical transformation, but it is one request per ~60k characters of source, so
 * the setting costs little in absolute terms.
 *
 * <p>A refusal is not an error here. The chapter is then translated with the glossary it
 * already has, which is how the whole pipeline worked before glossaries existed.
 */
public class AnthropicTermExtractionAdapter implements TermExtractionPort {

    private static final Logger log = LoggerFactory.getLogger(AnthropicTermExtractionAdapter.class);

    private static final long MAX_TOKENS = 16_000;

    private static final String INSTRUCTIONS = """
            You are building the terminology glossary for the translation of a medical
            textbook used by physicians and residents. Every later page will be translated
            using exactly the renderings you choose, so they must be the established,
            standard terms used in medical literature in the target language.

            From the text you are given, pick the terms whose translation must stay identical
            throughout the book:
            - diseases, syndromes and clinical signs
            - anatomical structures (use the target language's standard anatomical term)
            - physiological and pathophysiological concepts
            - procedures, investigations and scoring systems
            - abbreviations whose expansion should be fixed

            Do not include:
            - drug names (INNs and brands stay verbatim anyway)
            - numbers, units, gene or protein symbols, ECG leads, classification codes
            - everyday words, or terms whose rendering is identical to the source
            - any term in the list of terms already in the glossary

            Write each source term exactly as it appears in the text, in its base form
            (singular, no article). Prefer the most specific multi-word term
            ("left ventricular hypertrophy" over "hypertrophy") when that is the unit a
            reader would look up.
            """;

    private final AnthropicClient client;
    private final AnthropicProperties properties;

    public AnthropicTermExtractionAdapter(AnthropicClient client, AnthropicProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public List<ProposedTerm> propose(Request request) {
        String known = request.knownSourceTerms().isEmpty()
                ? "(none yet)"
                : String.join("\n", request.knownSourceTerms());

        var params = MessageCreateParams.builder()
                .model(properties.model())
                .maxTokens(MAX_TOKENS)
                .systemOfTextBlockParams(List.of(TextBlockParam.builder().text(INSTRUCTIONS).build()))
                .addUserMessage("""
                        TARGET LANGUAGE: %s (%s)
                        MAXIMUM NEW TERMS: %d

                        TERMS ALREADY IN THE GLOSSARY:
                        %s

                        SOURCE TEXT:
                        %s
                        """.formatted(request.targetLanguage().englishName(),
                        request.targetLanguage().tag(), request.maxTerms(), known, request.text()))
                .outputConfig(StructuredOutputConfig.<ExtractionResponse>builder()
                        .effort(OutputConfig.Effort.MEDIUM)
                        .format(ExtractionResponse.class, JsonSchemaLocalValidation.NO)
                        .build())
                .build();

        StructuredMessage<ExtractionResponse> response;
        try {
            response = client.messages().create(params);
        } catch (AnthropicServiceException e) {
            throw new IllegalStateException("Anthropic term extraction failed: " + e.getMessage(), e);
        }

        if (response.stopReason().filter(StopReason.REFUSAL::equals).isPresent()) {
            log.warn("Term extraction was refused; continuing with the existing glossary");
            return List.of();
        }

        return response.content().stream()
                .flatMap(block -> block.text().stream())
                .map(typed -> typed.text())
                .filter(Objects::nonNull)
                .flatMap(payload -> payload.terms() == null ? java.util.stream.Stream.empty()
                        : payload.terms().stream())
                .filter(Objects::nonNull)
                .filter(term -> term.source() != null && term.target() != null)
                .map(term -> new ProposedTerm(term.source(), term.target(), kindOf(term.kind())))
                .toList();
    }

    private static TermKind kindOf(String kind) {
        if (kind == null) return TermKind.OTHER;
        try {
            return TermKind.valueOf(kind.strip().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return TermKind.OTHER;
        }
    }

    // --- structured output shape -------------------------------------------

    public record ExtractionResponse(
            @JsonPropertyDescription("The new glossary terms, most important first.")
            List<ExtractedTerm> terms) {}

    public record ExtractedTerm(
            @JsonPropertyDescription("The term exactly as written in the source text, base form.")
            String source,

            @JsonPropertyDescription(
                    "The standard rendering in the target language used in medical literature.")
            String target,

            @JsonPropertyDescription(
                    "One of ANATOMY, CONDITION, ABBREVIATION, OTHER.")
            String kind) {}
}
