package com.medlearn.learning.adapter.ai;

import com.medlearn.learning.domain.StudyGuide;
import com.medlearn.learning.domain.port.StudyGuideModelPort;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * Builds a structurally complete study guide from the chapter text without calling anything.
 *
 * <p>The content is mechanical - sentences lifted from the chapter - and makes no claim to be
 * useful study material. Its purpose is to exercise the rest of the path for free: the
 * record shape, the PDF template, fonts, pagination and the download endpoint. Those are
 * where the bugs live, and none of them need a model to find.
 */
@Component
public class DryRunStudyGuideAdapter implements StudyGuideModelPort {

    private static final int SAMPLE_SENTENCES = 6;

    @Override
    public boolean billable() {
        return false;
    }

    @Override
    public StudyGuide generate(Request request) {
        List<String> sentences = Arrays.stream(request.translatedText().split("(?<=\\.)\\s+"))
                .map(String::strip)
                .filter(sentence -> sentence.length() > 30)
                .limit(SAMPLE_SENTENCES)
                .toList();

        return new StudyGuide(
                request.chapterTitle(),
                sentences.stream().limit(3)
                        .map(sentence -> "[dry run] Understand: " + shorten(sentence)).toList(),
                sentences.stream().limit(4)
                        .map(sentence -> new StudyGuide.Concept(
                                firstWords(sentence, 3), shorten(sentence),
                                "[dry run] placeholder rationale"))
                        .toList(),
                sentences.stream().limit(3).toList(),
                sentences.stream().limit(2).toList(),
                List.of("[dry run] no red flags are derived without a model"),
                List.of(),
                sentences.stream().limit(3).toList(),
                sentences.stream().limit(3)
                        .map(sentence -> new StudyGuide.SelfTestItem(
                                "[dry run] What does this state? " + firstWords(sentence, 6),
                                shorten(sentence),
                                "[dry run] placeholder explanation"))
                        .toList());
    }

    private static String shorten(String sentence) {
        return sentence.length() <= 160 ? sentence : sentence.substring(0, 157) + "...";
    }

    private static String firstWords(String sentence, int count) {
        String[] words = sentence.split("\\s+");
        return String.join(" ", Arrays.copyOfRange(words, 0, Math.min(count, words.length)));
    }
}
