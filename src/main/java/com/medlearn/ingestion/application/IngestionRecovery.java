package com.medlearn.ingestion.application;

import com.medlearn.library.domain.port.TextbookRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

/**
 * Fails textbooks whose ingestion was cut short by the previous process stopping.
 *
 * <p>Without this, a restart during extraction leaves a textbook in {@code EXTRACTING}
 * forever: no job is running for it, it cannot be translated, and re-uploading reports it as
 * already ingested. Marked {@code FAILED}, re-uploading the same file retries it.
 *
 * <p>Runs before the web server accepts requests, so no ingest of this process can be
 * in flight yet.
 */
@Component
class IngestionRecovery implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(IngestionRecovery.class);

    private final TextbookRepository textbooks;

    IngestionRecovery(TextbookRepository textbooks) {
        this.textbooks = textbooks;
    }

    @Override
    public void afterSingletonsInstantiated() {
        int failed = textbooks.failUnfinishedIngestion();
        if (failed > 0) {
            log.warn("Marked {} textbook(s) whose ingestion was interrupted as failed; "
                    + "upload the same file again to retry", failed);
        }
    }
}
