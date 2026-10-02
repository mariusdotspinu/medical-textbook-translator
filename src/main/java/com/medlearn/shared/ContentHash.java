package com.medlearn.shared;

import java.util.HexFormat;

/**
 * Lowercase hex SHA-256 of a byte stream.
 *
 * <p>Used two ways: as the content address of an uploaded PDF (so re-uploading the same
 * book is a no-op), and as the translation-memory key for a single text block.
 */
public record ContentHash(String hex) {

    private static final int SHA256_HEX_LENGTH = 64;

    public ContentHash {
        if (hex == null || hex.length() != SHA256_HEX_LENGTH) {
            throw new IllegalArgumentException(
                    "expected a %d-character hex SHA-256, got: %s".formatted(SHA256_HEX_LENGTH, hex));
        }
        if (!hex.chars().allMatch(c -> (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
            throw new IllegalArgumentException("SHA-256 must be lowercase hex: " + hex);
        }
    }

    public static ContentHash ofDigest(byte[] digest) {
        return new ContentHash(HexFormat.of().formatHex(digest));
    }

    /**
     * Hash of a piece of text, used as the translation-memory key.
     *
     * <p>Hashing rather than storing the source text keeps the key a fixed 64 bytes however
     * long the paragraph is, and makes the primary key usable directly.
     */
    public static ContentHash ofText(String text) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            return ofDigest(digest.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required but unavailable", e);
        }
    }

    /** First two hex characters, used to shard the blob store directory tree. */
    public String shardPrefix() {
        return hex.substring(0, 2);
    }

    @Override
    public String toString() {
        return hex;
    }
}
