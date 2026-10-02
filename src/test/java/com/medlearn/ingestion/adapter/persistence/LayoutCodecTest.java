package com.medlearn.ingestion.adapter.persistence;

import com.medlearn.ingestion.domain.BlockBox;
import com.medlearn.ingestion.domain.FontInfo;
import com.medlearn.ingestion.domain.TextBlock;
import com.medlearn.ingestion.domain.TextRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LayoutCodecTest {

    private final LayoutCodec codec = new LayoutCodec();

    @Test
    @DisplayName("blocks survive a compress/decompress round trip unchanged")
    void roundTripsLosslessly() {
        List<TextBlock> original = List.of(
                new TextBlock("p1-b0", "Acute myocardial infarction",
                        new BlockBox(50.5f, 100.25f, 220f, 12f),
                        new FontInfo("ABCDEF+Minion-Regular", 10.5f, false, false),
                        0, 0, TextRole.BODY),
                new TextBlock("p1-b1", "Figure 1.2 Coronary circulation",
                        new BlockBox(50f, 400f, 180f, 9f),
                        new FontInfo("Helvetica-Oblique", 8f, false, true),
                        1, 1, TextRole.CAPTION));

        assertThat(codec.decode(codec.encode(original))).isEqualTo(original);
    }

    @Test
    @DisplayName("a realistic page compresses to roughly the size the storage estimate assumes")
    void compressesAPageToAFewKilobytes() {
        // The ~6MB-per-1000-page-book figure rests on pages landing near 3KB compressed.
        // If this regresses, the storage estimate is wrong and should be corrected rather
        // than quietly exceeded.
        List<TextBlock> page = realisticMedicalPage();

        byte[] encoded = codec.encode(page);
        int rawEstimate = page.stream().mapToInt(block -> block.text().length()).sum();

        assertThat(codec.decode(encoded)).hasSize(page.size());
        assertThat(encoded.length)
                .as("compressed page size (%d blocks, %d chars of text)", page.size(), rawEstimate)
                .isLessThan(6_000);
    }

    @Test
    void handlesAnEmptyPage() {
        assertThat(codec.decode(codec.encode(List.of()))).isEmpty();
    }

    /** ~45 lines of two-column body text, which is a typical dense textbook page. */
    private static List<TextBlock> realisticMedicalPage() {
        String[] sentences = {
                "The left anterior descending artery supplies the anterior two-thirds of the septum.",
                "Troponin I rises within 3 to 4 hours and remains elevated for up to 10 days.",
                "Administer aspirin 300 mg orally unless contraindicated by active bleeding.",
                "ST-segment elevation in leads V1 through V4 indicates an anterior infarction.",
        };
        List<TextBlock> blocks = new ArrayList<>();
        for (int i = 0; i < 45; i++) {
            blocks.add(new TextBlock(
                    TextBlock.idFor(1, i),
                    sentences[i % sentences.length],
                    new BlockBox(50f + (i % 2) * 270f, 80f + (i / 2) * 15f, 240f, 11f),
                    new FontInfo("ABCDEF+Minion-Regular", 9.5f, false, false),
                    i % 2, i, TextRole.BODY));
        }
        return List.copyOf(blocks);
    }
}
