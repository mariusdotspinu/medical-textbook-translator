package com.medlearn.platform.compress;

import com.github.luben.zstd.Zstd;

/**
 * zstd compression for the two columns that would otherwise dominate database size.
 *
 * <p>Postgres TOAST already compresses large values with pglz, so this is not the
 * difference between storing and not storing - it is roughly a further 3-5x on JSON-shaped
 * text, which is what keeps a 1000-page book near 6MB of database rather than ~25MB.
 *
 * <p>Level 9 is deliberate: compression happens once during ingest where a few extra
 * milliseconds per page are invisible, while decompression speed is level-independent.
 * The default (3) would give up a noticeable amount of ratio for speed nothing needs.
 */
public final class ZstdCodec {

    private static final int COMPRESSION_LEVEL = 9;

    /** Refuses absurd frame sizes rather than attempting a huge allocation. */
    private static final long MAX_DECOMPRESSED_BYTES = 64L * 1024 * 1024;

    private ZstdCodec() {}

    public static byte[] compress(byte[] raw) {
        if (raw == null) throw new IllegalArgumentException("nothing to compress");
        return Zstd.compress(raw, COMPRESSION_LEVEL);
    }

    public static byte[] decompress(byte[] compressed) {
        if (compressed == null || compressed.length == 0) {
            throw new IllegalArgumentException("nothing to decompress");
        }
        long size = Zstd.decompressedSize(compressed);
        if (size <= 0 || size > MAX_DECOMPRESSED_BYTES) {
            throw new IllegalStateException(
                    "zstd frame declares an implausible size: " + size);
        }
        return Zstd.decompress(compressed, (int) size);
    }
}
