package com.medlearn.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * @param root directory holding original PDFs and generated artifacts. Original textbooks
 *             never go into Postgres - a 500MB bytea would be both slow and pointless, since
 *             PDF streams are already compressed.
 */
@ConfigurationProperties(prefix = "medlearn.blob-store")
public record BlobStoreProperties(Path root) {
    public BlobStoreProperties {
        if (root == null) root = Path.of("./data/blobs");
    }
}
