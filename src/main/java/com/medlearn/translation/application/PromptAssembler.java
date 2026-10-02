package com.medlearn.translation.application;

import com.medlearn.shared.LanguageTag;
import com.medlearn.translation.domain.GlossaryTerm;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Builds the prompt, in the order prompt caching requires.
 *
 * <p>Caching is a strict prefix match over the rendered bytes, so the assembly is ordered by
 * how often each part changes:
 *
 * <pre>
 *   system[0]  frozen translator instructions      never change
 *   system[1]  book style guide + chapter context  once per chapter
 *   system[2]  glossary for this chapter           once per chapter   &lt;- cache breakpoint
 *   messages   the blocks of this batch            every request
 * </pre>
 *
 * <p>The breakpoint belongs at the end of the <em>shared</em> portion. Putting it after the
 * blocks - the obvious-looking choice - would write a new cache entry for every request and
 * read none of them back, turning caching into a pure surcharge.
 *
 * <p>Everything here must be byte-stable for a given chapter. A timestamp, a batch counter
 * or an unsorted glossary anywhere in the prefix silently invalidates the cache on every
 * request, with no error and a severalfold cost increase.
 */
@Component
public class PromptAssembler {

    /**
     * Frozen system prompt. Never interpolate anything into this: it sits at the very front
     * of the cached prefix, so a single varying character invalidates everything after it.
     */
    private static final String INSTRUCTIONS = """
            You are translating a medical textbook used by physicians and residents in training.
            Accuracy matters more than fluency, and a mistranslated dose or laterality is a
            clinical hazard rather than a stylistic matter. Follow these rules exactly.

            PRESERVE VERBATIM, never translate, localise, convert or reformat:
            - All numbers, doses, concentrations, rates and ranges, with their units
              (mg, mcg, g, mL, L, mmHg, mEq/L, IU, %, °C). Do not convert between unit systems.
            - Laboratory values and reference ranges.
            - Drug international non-proprietary names (INNs) and brand names.
            - Gene and protein symbols (BRCA1, TP53, HER2), which stay in their original form.
            - ECG lead names (V1-V6, aVR), anatomical shorthand, and classification codes
              (NYHA III, Killip II, ICD-10 codes, TNM stages).
            - Figure, table, box and equation numbers, and any cross-reference to them.
            - Citation markers and reference numbers.
            - Abbreviations and acronyms: keep the original, and on first use in a block add
              the expansion in the target language in parentheses.

            TERMINOLOGY:
            - Latin anatomical nomenclature (Terminologia Anatomica) stays in Latin.
            - Use the glossary supplied below for every term it covers, without exception.
              Consistency across the whole book matters more than local elegance.
            - Where the target language has no settled term, keep the source term and add the
              closest target-language rendering in parentheses on first use.

            FIDELITY:
            - Translate the complete text. Never summarise, abridge, explain, correct, or add
              commentary, even where the source appears to contain an error.
            - Preserve emphasis, enumeration, and sentence boundaries.
            - If a block is a fragment, a heading, or a table cell, translate it as such and do
              not expand it into a sentence.

            OUTPUT:
            - Return one entry per input block, with the block's id exactly as given.
            - Return every block you were given, in the same order, even if a block needs no
              change - in that case repeat it unchanged.
            """;

    /** The per-book half of the prefix: style guide plus what this chapter is about. */
    public String bookAndChapterContext(
            LanguageTag targetLanguage, String styleGuide, String chapterContext) {

        StringBuilder context = new StringBuilder(512);
        context.append("TARGET LANGUAGE: ")
                .append(targetLanguage.englishName())
                .append(" (")
                .append(targetLanguage.tag())
                .append(")\n\n");

        if (!styleGuide.isBlank()) {
            context.append("BOOK STYLE GUIDE:\n").append(styleGuide.strip()).append("\n\n");
        }
        if (!chapterContext.isBlank()) {
            context.append("CHAPTER CONTEXT (for disambiguation only, do not translate this):\n")
                    .append(chapterContext.strip())
                    .append('\n');
        }
        return context.toString();
    }

    /**
     * The glossary block.
     *
     * <p>Sorted by source term, always. An unsorted list would render different bytes on
     * different runs for the same glossary and silently break the cache - one of the few
     * failure modes here that costs money without producing any visible symptom.
     */
    public String glossaryBlock(List<GlossaryTerm> glossary) {
        if (glossary.isEmpty()) {
            return "GLOSSARY: none established yet for this book.\n";
        }

        StringBuilder block = new StringBuilder(glossary.size() * 48);
        block.append("GLOSSARY - use these renderings exactly:\n");
        glossary.stream()
                .sorted(java.util.Comparator.comparing(GlossaryTerm::sourceTerm))
                .forEach(term -> block.append("  ")
                        .append(term.sourceTerm())
                        .append(" -> ")
                        .append(term.targetTerm())
                        .append("  [")
                        .append(term.kind())
                        .append("]\n"));
        return block.toString();
    }

    public String instructions() {
        return INSTRUCTIONS;
    }

    /**
     * The varying part: the blocks themselves, after the cache breakpoint.
     *
     * <p>Rendered as explicit id/text pairs rather than prose so that a block whose text
     * happens to look like an instruction cannot be read as one.
     */
    public String renderBatch(List<com.medlearn.translation.domain.port.TranslationModelPort.SourceBlock> blocks) {
        StringBuilder payload = new StringBuilder(blocks.size() * 200);
        payload.append("Translate each block below. Return one entry per block, keyed by id.\n\n");
        for (var block : blocks) {
            payload.append("<block id=\"").append(block.id()).append("\">\n")
                    .append(block.text()).append("\n</block>\n\n");
        }
        return payload.toString();
    }
}
