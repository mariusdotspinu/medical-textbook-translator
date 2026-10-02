package com.medlearn.translation.adapter.ai;

import com.medlearn.translation.domain.TranslatedBlock;
import com.medlearn.translation.domain.port.TranslationModelPort;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * A translator that calls nothing, used to exercise the whole pipeline for free.
 *
 * <p>Rendering fidelity is the riskiest part of this application and has nothing to do with
 * the model. With this adapter a chapter can be extracted, "translated", rendered and
 * inspected without an API key and without spending anything - which means overlay fitting,
 * masking, fonts and pagination can be debugged in a tight loop instead of a paid one.
 *
 * <p>It does two things that make the dry run genuinely representative rather than a no-op:
 *
 * <ul>
 *   <li><b>Expands the text by about a quarter.</b> Romance and Slavic translations run
 *       15-30% longer than English, so identity output would make every box fit and prove
 *       nothing. Expansion is what exercises the overlay fitting ladder and the reflow
 *       fallback.
 *   <li><b>Injects diacritics.</b> Substituting a few accented characters exercises font
 *       embedding and glyph coverage - the path whose failure mode is a silent page of blank
 *       boxes.
 * </ul>
 */
@Component
public class DryRunTranslationAdapter implements TranslationModelPort {

    /** Every nth word is duplicated, giving roughly a 25% length increase. */
    private static final int EXPANSION_EVERY_NTH_WORD = 4;

    @Override
    public String name() {
        return "dry-run";
    }

    @Override
    public boolean billable() {
        return false;
    }

    @Override
    public Result translate(Batch batch) {
        List<TranslatedBlock> translated = new ArrayList<>(batch.blocks().size());
        for (SourceBlock block : batch.blocks()) {
            translated.add(new TranslatedBlock(block.id(), simulate(block.text())));
        }
        // No usage: nothing was billed, and reporting fake tokens would corrupt the cost
        // figures the job result reports.
        return new Result(translated, List.of(), Usage.none());
    }

    private static String simulate(String source) {
        String[] words = source.split(" ");
        StringBuilder expanded = new StringBuilder(source.length() + source.length() / 4);

        for (int i = 0; i < words.length; i++) {
            if (i > 0) expanded.append(' ');
            String word = accent(words[i]);
            expanded.append(word);
            // Never duplicate a word containing digits: doing so changes the numbers in the
            // text, which the quality gate correctly reports as an altered dosage. A dry run
            // should exercise the layout, not manufacture findings.
            boolean expandable = word.chars().noneMatch(Character::isDigit);
            if (expandable && i % EXPANSION_EVERY_NTH_WORD == EXPANSION_EVERY_NTH_WORD - 1) {
                expanded.append(' ').append(word);
            }
        }
        return expanded.toString();
    }

    /** Romanian-flavoured substitutions: enough to prove the font carries the glyphs. */
    private static String accent(String word) {
        if (word.length() < 4) return word;
        return word.replace("at", "ăt").replace("is", "iș").replace("ti", "ți");
    }
}
