package com.medlearn.shared;

/**
 * Opaque pointer to a stored binary, resolved by whichever {@code BlobStorePort}
 * implementation is active (filesystem locally, object storage later).
 *
 * <p>Deliberately a plain key rather than a path or URL so that swapping the
 * filesystem store for S3/MinIO needs no change above the adapter.
 */
public record BlobRef(String key) {
    public BlobRef {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("BlobRef key is required");
        }
        if (key.contains("..")) {
            throw new IllegalArgumentException("BlobRef key must not traverse directories: " + key);
        }
    }

    @Override
    public String toString() {
        return key;
    }
}
