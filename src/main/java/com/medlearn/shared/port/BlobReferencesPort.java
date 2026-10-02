package com.medlearn.shared.port;

import com.medlearn.shared.BlobRef;

import java.util.Set;

/**
 * The blobs one context still points at.
 *
 * <p>Each context that stores files implements this for its own rows (textbook originals,
 * job artifacts), so the orphan sweep can tell which files are safe to delete without
 * reading any context's tables itself.
 */
public interface BlobReferencesPort {

    Set<BlobRef> referencedBlobs();
}
