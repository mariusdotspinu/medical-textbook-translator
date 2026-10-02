package com.medlearn.platform.blob;

import com.medlearn.platform.config.BlobStoreProperties;
import com.medlearn.shared.port.BlobStorePort.BlobEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FilesystemBlobStoreListingTest {

    @TempDir
    Path root;

    @Test
    @DisplayName("stored blobs are listed by their ref; writes in progress are listed separately")
    void separatesFinishedBlobsFromIncompleteWrites() throws Exception {
        FilesystemBlobStore store = new FilesystemBlobStore(new BlobStoreProperties(root));
        var stored = store.storeBytes(new byte[]{1, 2, 3}, "chapter.pdf");
        Files.writeString(root.resolve("incoming/blob-123.part"), "half an upload");

        assertThat(store.list()).extracting(BlobEntry::ref).containsExactly(stored.ref());
        assertThat(store.list().getFirst().byteSize()).isEqualTo(3);

        assertThat(store.listIncompleteWrites()).singleElement().satisfies(entry ->
                assertThat(entry.ref().key()).isEqualTo("incoming/blob-123.part"));

        // An incomplete write's ref is deletable through the normal path.
        store.delete(store.listIncompleteWrites().getFirst().ref());
        assertThat(store.listIncompleteWrites()).isEmpty();
    }
}
