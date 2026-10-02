package com.medlearn.ingestion.domain;

import com.medlearn.ingestion.domain.OutlineTreeBuilder.FlatEntry;
import com.medlearn.shared.PageRange;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OutlineTreeBuilderTest {

    @Test
    @DisplayName("a chapter ends before the next chapter, not before its own first section")
    void nestsAndClosesRanges() {
        List<OutlineNode> tree = OutlineTreeBuilder.close(OutlineTreeBuilder.nest(List.of(
                new FlatEntry("1 Heart Failure", 0, 3),
                new FlatEntry("1.1 Pathophysiology", 1, 3),
                new FlatEntry("1.2 Treatment", 1, 6),
                new FlatEntry("2 Arrhythmias", 0, 11))), 20);

        assertThat(tree).hasSize(2);
        OutlineNode first = tree.getFirst();
        assertThat(first.range()).isEqualTo(PageRange.of(3, 10));
        assertThat(first.children()).extracting(OutlineNode::range)
                .containsExactly(PageRange.of(3, 5), PageRange.of(6, 10));
        assertThat(tree.get(1).range()).isEqualTo(PageRange.of(11, 20));
    }

    @Test
    @DisplayName("a jump of several levels nests one level deeper")
    void normalisesLevelJumps() {
        List<OutlineTreeBuilder.Draft> drafts = OutlineTreeBuilder.nest(List.of(
                new FlatEntry("Part I", 0, 1),
                new FlatEntry("deep section", 3, 2)));

        assertThat(drafts.getFirst().children()).singleElement()
                .satisfies(child -> assertThat(child.level()).isEqualTo(1));
    }
}
