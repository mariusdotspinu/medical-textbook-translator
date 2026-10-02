package com.medlearn.translation.domain;

import com.medlearn.translation.domain.port.TranslationModelPort.SourceBlock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BatchPlannerTest {

    private static SourceBlock block(String id, int length) {
        return new SourceBlock(id, "x".repeat(length));
    }

    @Test
    @DisplayName("batches stay under the character budget")
    void respectsCharacterBudget() {
        List<SourceBlock> blocks = new ArrayList<>();
        for (int i = 0; i < 50; i++) blocks.add(block("b" + i, 300));

        List<List<SourceBlock>> batches = BatchPlanner.intoBatches(blocks, 1000);

        assertThat(batches).isNotEmpty();
        for (List<SourceBlock> batch : batches) {
            int size = batch.stream().mapToInt(b -> b.text().length()).sum();
            assertThat(size).isLessThanOrEqualTo(1000);
        }
    }

    @Test
    @DisplayName("every block appears exactly once, in order")
    void losesNothing() {
        List<SourceBlock> blocks = new ArrayList<>();
        for (int i = 0; i < 37; i++) blocks.add(block("b" + i, 137));

        List<String> flattened = BatchPlanner.intoBatches(blocks, 1000).stream()
                .flatMap(List::stream).map(SourceBlock::id).toList();

        assertThat(flattened).hasSize(37);
        assertThat(flattened).isEqualTo(blocks.stream().map(SourceBlock::id).toList());
    }

    @Test
    @DisplayName("a single block larger than the budget is still emitted, alone")
    void doesNotDropAnOversizedBlock() {
        // Splitting a block would break the id-to-text correspondence the whole design
        // depends on, so an oversized block goes out on its own instead.
        List<SourceBlock> blocks = List.of(block("small", 100), block("huge", 5000), block("tail", 100));

        List<List<SourceBlock>> batches = BatchPlanner.intoBatches(blocks, 1000);

        assertThat(batches.stream().flatMap(List::stream).map(SourceBlock::id))
                .containsExactly("small", "huge", "tail");
        assertThat(batches.stream().filter(b -> b.size() == 1
                && b.getFirst().id().equals("huge"))).hasSize(1);
    }

    @Test
    void handlesEmptyInput() {
        assertThat(BatchPlanner.intoBatches(List.of(), 1000)).isEmpty();
        assertThat(BatchPlanner.intoBatches(null, 1000)).isEmpty();
    }
}
