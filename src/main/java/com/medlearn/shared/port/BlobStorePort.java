package com.medlearn.shared.port;

import com.medlearn.shared.BlobRef;
import com.medlearn.shared.ContentHash;

import java.io.InputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * Binary storage for original PDFs and generated artifacts.
 *
 * <p>Lives in the shared kernel rather than in one bounded context because ingestion
 * stores source PDFs while translation and learning store generated ones.
 *
 * <p>Implementations are content-addressed: storing the same bytes twice yields the same
 * {@link BlobRef}, which is what makes re-uploading a textbook a no-op.
 */
public interface BlobStorePort {

    /**
     * Streams {@code source} to storage, hashing as it goes.
     *
     * <p>Must not buffer the whole stream in memory - the inputs here are 500MB PDFs.
     *
     * @param filenameHint used only to pick a file extension
     */
    StoredBlob store(InputStream source, String filenameHint);

    /** For generated artifacts, which are produced in memory and are far smaller. */
    StoredBlob storeBytes(byte[] content, String filenameHint);

    /**
     * Materialises the blob as a local file.
     *
     * <p>PDFBox needs random access to a real file to avoid loading the document into
     * heap, so this exists rather than only {@link #open}. A remote implementation would
     * download to a temp file here.
     */
    Path resolveToLocalFile(BlobRef ref);

    InputStream open(BlobRef ref);

    long sizeOf(BlobRef ref);

    boolean exists(BlobRef ref);

    void delete(BlobRef ref);

    /** Every stored blob, for maintenance. Excludes writes still in progress. */
    List<BlobEntry> list();

    /**
     * Temporary files of writes that never completed, such as an upload cut off by a crash.
     * Their refs can be passed to {@link #delete}. Includes writes in progress right now, so
     * callers must only remove old ones.
     */
    List<BlobEntry> listIncompleteWrites();

    record StoredBlob(BlobRef ref, ContentHash hash, long byteSize) {}

    record BlobEntry(BlobRef ref, long byteSize, Instant modifiedAt) {}
}
