package com.medlearn.translation.application;

import com.medlearn.platform.config.TranslationProperties;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/**
 * Requests to the model in flight across <em>all</em> jobs, not per job.
 *
 * <p>The API rate limit belongs to the account, so two jobs running together must share it,
 * and so must glossary extraction and translation. Fair, so a second job's requests
 * interleave with the first job's instead of waiting for all of them.
 *
 * <p>Callers check cancellation <em>after</em> getting a slot: a request that queued behind
 * other jobs must not be sent once its own job is cancelled.
 */
@Component
public class ApiSlots {

    private final Semaphore slots;

    public ApiSlots(TranslationProperties properties) {
        this.slots = new Semaphore(properties.maxInFlight(), true);
    }

    public <T> T call(Supplier<T> request) {
        try {
            slots.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for an API slot", e);
        }
        try {
            return request.get();
        } finally {
            slots.release();
        }
    }

    public void run(Runnable request) {
        call(() -> {
            request.run();
            return null;
        });
    }
}
