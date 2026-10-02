package com.medlearn.ingestion.domain;

import com.medlearn.ingestion.domain.TocParser.TocEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TocPageMapperTest {

    private final List<TocEntry> entries = List.of(
            new TocEntry("1 Heart Failure", 0, 1),
            new TocEntry("2 Arrhythmias", 0, 9),
            new TocEntry("3 Valvular Disease", 0, 15));

    @Test
    @DisplayName("recognised page numbers give the offset directly")
    void usesFolios() {
        Map<Integer, Integer> folios = new HashMap<>();
        for (int pdf = 13; pdf <= 40; pdf++) folios.put(pdf, pdf - 12);

        var mapping = TocPageMapper.map(entries, folios, page -> "", 13, 40).orElseThrow();

        assertThat(mapping.offset()).isEqualTo(12);
        assertThat(mapping.basis()).isEqualTo("printed page numbers");
    }

    @Test
    @DisplayName("without page numbers, the offset is where the titles appear in the body text")
    void usesTitles() {
        Map<Integer, String> body = Map.of(
                13, "CHAPTER 1: HEART FAILURE\nHeart failure is a syndrome",
                21, "Chapter 2 — Arrhythmias\nAtrial fibrillation",
                27, "3 Valvular disease\nAortic stenosis");

        var mapping = TocPageMapper.map(entries, Map.of(), page -> body.getOrDefault(page, "text"), 5, 40)
                .orElseThrow();

        assertThat(mapping.offset()).isEqualTo(12);
    }

    @Test
    @DisplayName("a TOC that cannot be placed is not used")
    void refusesToGuess() {
        assertThat(TocPageMapper.map(entries, Map.of(), page -> "unrelated text", 5, 40)).isEmpty();
    }

    @Test
    @DisplayName("titles are compared as letters only, without chapter numbering")
    void normalisesTitles() {
        assertThat(TocPageMapper.letters("Chapter 12: Heart-Failure (HF)")).isEqualTo("heart failure hf");
        assertThat(TocPageMapper.letters("2.1 Atrial fibrillation")).isEqualTo("atrial fibrillation");
    }
}
