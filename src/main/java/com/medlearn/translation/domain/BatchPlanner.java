package com.medlearn.translation.domain;

import com.medlearn.translation.domain.port.TranslationModelPort.SourceBlock;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Gatherer;

/**
 * Groups blocks into request-sized batches.
 *
 * <p>Batch size is bounded by <em>characters</em>, not by block count. Blocks range from a
 * two-word heading to a 900-character paragraph, so a fixed count would produce requests
 * varying by an order of magnitude - some wasting the round trip, others overrunning the
 * output limit and truncating mid-paragraph.
 *
 * <p>Implemented as a custom {@link Gatherer} because that is exactly the shape of the
 * problem: a sequential, state-carrying, variable-size window over a stream.
 * {@code Gatherers.windowFixed} cannot express it, since the window boundary depends on
 * accumulated size rather than on a count.
 */
public final class BatchPlanner {

    /**
     * Roughly 6k tokens of source per request at ~4 characters per token. Large enough that
     * the cached prompt prefix is amortised over real work, small enough to stay clear of
     * the output limit once the translation expands.
     */
    public static final int DEFAULT_MAX_CHARS_PER_BATCH = 24_000;

    private BatchPlanner() {}

    public static List<List<SourceBlock>> intoBatches(List<SourceBlock> blocks, int maxChars) {
        if (blocks == null || blocks.isEmpty()) return List.of();
        return blocks.stream().gather(windowedByCharacters(maxChars)).toList();
    }

    static Gatherer<SourceBlock, ?, List<SourceBlock>> windowedByCharacters(int maxChars) {
        int limit = Math.max(1, maxChars);

        return Gatherer.ofSequential(
                Window::new,
                Gatherer.Integrator.ofGreedy((window, block, downstream) -> {
                    int length = block.text().length();

                    // Close the window before adding, so a batch only exceeds the limit when
                    // a single block is itself larger than it - which cannot be split here.
                    if (!window.blocks.isEmpty() && window.characters + length > limit) {
                        downstream.push(List.copyOf(window.blocks));
                        window.blocks.clear();
                        window.characters = 0;
                    }
                    window.blocks.add(block);
                    window.characters += length;
                    return true;
                }),
                (window, downstream) -> {
                    if (!window.blocks.isEmpty()) downstream.push(List.copyOf(window.blocks));
                });
    }

    /** Mutable accumulator for the gatherer. */
    private static final class Window {
        private final List<SourceBlock> blocks = new ArrayList<>();
        private int characters;
    }
}
