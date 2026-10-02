package com.medlearn.ingestion.domain;

import com.medlearn.ingestion.domain.TocParser.ParsedToc;
import com.medlearn.ingestion.domain.TocParser.TocEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

class TocParserTest {

    private static final float LEFT = 72f;
    private static final float INDENT = 90f;

    @Test
    @DisplayName("entries with dot leaders become titles, levels from indentation, and printed pages")
    void parsesEntriesAndLevels() {
        SortedMap<Integer, List<TextBlock>> pages = new TreeMap<>();
        pages.put(1, page(1, line(LEFT, "Clinical Cardiology")));
        pages.put(2, page(2,
                line(LEFT, "Contents"),
                line(LEFT, "Preface . . . . . . . . . . . . vii"),
                line(LEFT, "1 Heart Failure ...................... 1"),
                line(INDENT, "1.1 Pathophysiology ............... 2"),
                line(INDENT, "1.2 Treatment ...................... 4"),
                line(LEFT, "2 Arrhythmias ....................... 9"),
                line(LEFT, "3 Valvular Heart Disease and"),
                line(LEFT, "Endocarditis ........................ 15")));
        pages.put(3, page(3, line(LEFT, "Chapter 1"), line(LEFT, "Heart failure is common.")));

        ParsedToc toc = TocParser.parse(pages).orElseThrow();

        assertThat(toc.firstPage()).isEqualTo(2);
        assertThat(toc.lastPage()).isEqualTo(2);
        assertThat(toc.entries()).containsExactly(
                new TocEntry("1 Heart Failure", 0, 1),
                new TocEntry("1.1 Pathophysiology", 1, 2),
                new TocEntry("1.2 Treatment", 1, 4),
                new TocEntry("2 Arrhythmias", 0, 9),
                new TocEntry("3 Valvular Heart Disease and Endocarditis", 0, 15));
    }

    @Test
    @DisplayName("a TOC continues over consecutive pages, without dot leaders too")
    void spansPagesWithoutLeaders() {
        SortedMap<Integer, List<TextBlock>> pages = new TreeMap<>();
        List<TextBlock> first = new ArrayList<>(List.of(line(LEFT, "Contents")));
        List<TextBlock> second = new ArrayList<>();
        for (int i = 1; i <= 6; i++) first.add(line(LEFT, "Chapter " + i + " Topic " + i + "  " + (i * 10)));
        for (int i = 7; i <= 12; i++) second.add(line(LEFT, "Chapter " + i + " Topic " + i + "  " + (i * 10)));
        pages.put(4, renumbered(4, first));
        pages.put(5, renumbered(5, second));

        ParsedToc toc = TocParser.parse(pages).orElseThrow();

        assertThat(toc.lastPage()).isEqualTo(5);
        assertThat(toc.entries()).hasSize(12);
        assertThat(toc.entries().getLast()).isEqualTo(new TocEntry("Chapter 12 Topic 12", 0, 120));
    }

    @Test
    @DisplayName("ordinary prose is not a table of contents, even with numbers at line ends")
    void ignoresProse() {
        SortedMap<Integer, List<TextBlock>> pages = new TreeMap<>();
        pages.put(1, page(1,
                line(LEFT, "Aspirin is given at a loading dose of 300"),
                line(LEFT, "mg orally, followed by a maintenance dose"),
                line(LEFT, "of 75 to 100 mg daily. Clopidogrel is added"),
                line(LEFT, "for patients undergoing stenting, and the"),
                line(LEFT, "combination continues for 12")));

        assertThat(TocParser.parse(pages)).isEmpty();
    }

    @Test
    @DisplayName("page numbers that keep going backwards mean this was not a TOC")
    void rejectsDisorderedNumbers() {
        SortedMap<Integer, List<TextBlock>> pages = new TreeMap<>();
        pages.put(1, page(1,
                line(LEFT, "Contents"),
                line(LEFT, "Normal values ........ 40"),
                line(LEFT, "Sodium ........ 3"),
                line(LEFT, "Potassium ........ 30"),
                line(LEFT, "Calcium ........ 2"),
                line(LEFT, "Magnesium ........ 25"),
                line(LEFT, "Phosphate ........ 1")));

        assertThat(TocParser.parse(pages)).isEmpty();
    }

    @Test
    @DisplayName("a list of figures after the contents is not read as chapters")
    void stopsAtListOfFigures() {
        SortedMap<Integer, List<TextBlock>> pages = new TreeMap<>();
        pages.put(1, page(1,
                line(LEFT, "Contents"),
                line(LEFT, "1 Anatomy ........ 1"),
                line(LEFT, "2 Physiology ........ 20"),
                line(LEFT, "3 Pathology ........ 40"),
                line(LEFT, "List of figures"),
                line(LEFT, "Figure 1.1 The heart ........ 3")));

        assertThat(TocParser.parse(pages).orElseThrow().entries())
                .extracting(TocEntry::title).containsExactly("1 Anatomy", "2 Physiology", "3 Pathology");
    }

    private static TextBlock line(float x, String text) {
        return new TextBlock("p0-b0", text, new BlockBox(x, 0, 400, 12),
                new FontInfo("Times", 11f, false, false), 0, 0, TextRole.BODY);
    }

    private static List<TextBlock> page(int pageNo, TextBlock... lines) {
        return renumbered(pageNo, List.of(lines));
    }

    /** Ids and vertical positions as the segmenter would assign them. */
    private static List<TextBlock> renumbered(int pageNo, List<TextBlock> lines) {
        List<TextBlock> out = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            TextBlock line = lines.get(i);
            out.add(new TextBlock("p" + pageNo + "-b" + i, line.text(),
                    new BlockBox(line.box().x(), 72 + i * 16, 400, 12), line.font(), 0, i, line.role()));
        }
        return out;
    }
}
