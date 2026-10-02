package com.medlearn.platform.blob;

import com.medlearn.platform.config.BlobStoreProperties;
import com.medlearn.shared.BlobRef;
import com.medlearn.shared.ContentHash;
import com.medlearn.shared.port.BlobStorePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Content-addressed blob storage on the local filesystem.
 *
 * <p>Layout is {@code <root>/<first two hex chars>/<sha256>.<ext>}. Sharding by the first
 * byte keeps directory sizes sane once a library grows.
 *
 * <p>Writes land in a temp file first and are moved into place only once the hash is known,
 * which gives three properties worth having: the final name is the content hash, a
 * re-upload of the same textbook is detected and costs nothing, and a failed or interrupted
 * upload can never leave a half-written blob under a valid name.
 *
 * <p>The stream is copied in fixed-size chunks and digested in the same pass - a 500MB PDF
 * is never held in memory.
 */
@Component
public class FilesystemBlobStore implements BlobStorePort {

    private static final Logger log = LoggerFactory.getLogger(FilesystemBlobStore.class);
    private static final int COPY_BUFFER_BYTES = 1 << 16;
    private static final String TEMP_SUBDIR = "incoming";

    private final Path root;

    public FilesystemBlobStore(BlobStoreProperties properties) {
        this.root = properties.root().toAbsolutePath().normalize();
        try {
            Files.createDirectories(root.resolve(TEMP_SUBDIR));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create blob store at " + root, e);
        }
        log.info("Blob store rooted at {}", root);
    }

    @Override
    public StoredBlob store(InputStream source, String filenameHint) {
        Path temp = newTempFile();
        try {
            MessageDigest digest = sha256();
            long bytes;
            try (OutputStream raw = Files.newOutputStream(temp);
                 DigestOutputStream digesting = new DigestOutputStream(raw, digest)) {
                bytes = copy(source, digesting);
            }
            ContentHash hash = ContentHash.ofDigest(digest.digest());
            return promote(temp, hash, bytes, extensionOf(filenameHint));
        } catch (IOException e) {
            deleteQuietly(temp);
            throw new UncheckedIOException("Failed storing blob", e);
        } catch (RuntimeException e) {
            deleteQuietly(temp);
            throw e;
        }
    }

    @Override
    public StoredBlob storeBytes(byte[] content, String filenameHint) {
        if (content == null) throw new IllegalArgumentException("content is required");
        ContentHash hash = ContentHash.ofDigest(sha256().digest(content));
        Path target = pathFor(hash, extensionOf(filenameHint));
        try {
            Files.createDirectories(target.getParent());
            if (!Files.exists(target)) {
                Path temp = newTempFile();
                Files.write(temp, content);
                moveIntoPlace(temp, target);
            }
            return new StoredBlob(refFor(hash, extensionOf(filenameHint)), hash, content.length);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed storing blob bytes", e);
        }
    }

    @Override
    public Path resolveToLocalFile(BlobRef ref) {
        Path path = root.resolve(ref.key()).normalize();
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException("Blob key escapes the store root: " + ref);
        }
        if (!Files.exists(path)) {
            throw new IllegalStateException("Blob is missing from the store: " + ref);
        }
        return path;
    }

    @Override
    public InputStream open(BlobRef ref) {
        try {
            return Files.newInputStream(resolveToLocalFile(ref));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot open blob " + ref, e);
        }
    }

    @Override
    public long sizeOf(BlobRef ref) {
        try {
            return Files.size(resolveToLocalFile(ref));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot size blob " + ref, e);
        }
    }

    @Override
    public boolean exists(BlobRef ref) {
        Path path = root.resolve(ref.key()).normalize();
        return path.startsWith(root) && Files.exists(path);
    }

    @Override
    public void delete(BlobRef ref) {
        deleteQuietly(root.resolve(ref.key()).normalize());
    }

    @Override
    public List<BlobEntry> list() {
        // Finished blobs live exactly two levels down: <shard>/<hash>.<ext>.
        try (Stream<Path> files = Files.walk(root, 2)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(path -> !path.startsWith(root.resolve(TEMP_SUBDIR)))
                    .map(this::entryOf)
                    .flatMap(Optional::stream)
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list blob store at " + root, e);
        }
    }

    @Override
    public List<BlobEntry> listIncompleteWrites() {
        try (Stream<Path> files = Files.list(root.resolve(TEMP_SUBDIR))) {
            return files
                    .filter(Files::isRegularFile)
                    .map(this::entryOf)
                    .flatMap(Optional::stream)
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list incomplete writes in " + root, e);
        }
    }

    // --- internals ---------------------------------------------------------

    /** Empty if the file vanished between listing and reading it. */
    private Optional<BlobEntry> entryOf(Path path) {
        try {
            String key = root.relativize(path).toString().replace('\\', '/');
            return Optional.of(new BlobEntry(new BlobRef(key), Files.size(path),
                    Files.getLastModifiedTime(path).toInstant()));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private StoredBlob promote(Path temp, ContentHash hash, long bytes, String extension)
            throws IOException {
        Path target = pathFor(hash, extension);
        Files.createDirectories(target.getParent());

        if (Files.exists(target)) {
            // Same content already stored - keep the original and drop the duplicate.
            log.info("Blob {} already present; discarding duplicate upload", hash);
            deleteQuietly(temp);
        } else {
            moveIntoPlace(temp, target);
        }
        return new StoredBlob(refFor(hash, extension), hash, bytes);
    }

    private static void moveIntoPlace(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            // Temp dir and target can be on different filesystems in some setups.
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Path pathFor(ContentHash hash, String extension) {
        return root.resolve(relativeKey(hash, extension));
    }

    private static BlobRef refFor(ContentHash hash, String extension) {
        return new BlobRef(relativeKey(hash, extension));
    }

    private static String relativeKey(ContentHash hash, String extension) {
        return "%s/%s%s".formatted(hash.shardPrefix(), hash.hex(), extension);
    }

    private Path newTempFile() {
        try {
            return Files.createTempFile(root.resolve(TEMP_SUBDIR), "blob-", ".part");
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create temp file in blob store", e);
        }
    }

    private static long copy(InputStream source, OutputStream sink) throws IOException {
        byte[] buffer = new byte[COPY_BUFFER_BYTES];
        long total = 0;
        int read;
        while ((read = source.read(buffer)) != -1) {
            sink.write(buffer, 0, read);
            total += read;
        }
        return total;
    }

    /** Lowercased extension including the dot, or empty. Never trusts the caller's path. */
    private static String extensionOf(String filenameHint) {
        if (filenameHint == null) return "";
        String name = Path.of(filenameHint).getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "";
        String extension = name.substring(dot).toLowerCase(java.util.Locale.ROOT);
        return extension.matches("\\.[a-z0-9]{1,8}") ? extension : "";
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required but unavailable", e);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Could not delete {}: {}", path, e.getMessage());
        }
    }
}
