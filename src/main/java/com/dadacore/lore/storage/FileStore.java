package com.dadacore.lore.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;

import com.dadacore.lore.LoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The imported files, on disk under {@code LORE_FILES_PATH}: {@code <organization>/ab/cd/<sha256>},
 * without their name or extension, written once and never changed. A file whose content is
 * already stored for the organization is kept once (deduplication).
 *
 * <p>A file arrives in {@code tmp/}, where its SHA-256 and its size are computed as it is
 * written, then moves to its place in one step. The extraction processes work in
 * {@code tmp/extraction/}, emptied at each start.
 */
@Service
public class FileStore {

    /** The beginning of a file, kept to check its format. */
    public static final int HEAD = 64 * 1024;

    /** A temporary file untouched for this long is left over. */
    static final java.time.Duration LEFT_FOR = java.time.Duration.ofHours(1);

    private static final Logger log = LoggerFactory.getLogger(FileStore.class);
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private final Path root;
    private final Path temporary;
    private final Path extraction;

    public FileStore(LoreProperties properties) {
        this.root = properties.filesPath().toAbsolutePath().normalize();
        this.temporary = root.resolve("tmp");
        this.extraction = temporary.resolve("extraction");
        prepare();
    }

    /** A file received, in the temporary folder until it is {@linkplain #keep kept} or {@linkplain #discard discarded}. */
    public record Received(UUID organizationId, Path temporary, String sha256, long size, byte[] head) {
    }

    /** The content went beyond {@code maxBytes}: nothing is left on disk. */
    public static final class TooLarge extends IOException {
        public TooLarge() {
            super("File too large");
        }
    }

    /**
     * Writes the content to the temporary folder, computing its SHA-256 and its size.
     *
     * @throws TooLarge beyond {@code maxBytes}
     */
    public Received receive(UUID organizationId, InputStream content, long maxBytes) throws IOException {
        Path file = Files.createTempFile(temporary, "upload-", ".part");
        MessageDigest digest = sha256();
        byte[] head = new byte[HEAD];
        int headLength = 0;
        long size = 0;
        try (OutputStream out = new DigestOutputStream(Files.newOutputStream(file), digest)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = content.read(buffer)) != -1) {
                size += read;
                if (size > maxBytes) {
                    throw new TooLarge();
                }
                if (headLength < HEAD) {
                    int copied = Math.min(read, HEAD - headLength);
                    System.arraycopy(buffer, 0, head, headLength, copied);
                    headLength += copied;
                }
                out.write(buffer, 0, read);
            }
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(file);
            throw e;
        }
        return new Received(organizationId, file, HexFormat.of().formatHex(digest.digest()), size,
                Arrays.copyOf(head, headLength));
    }

    /** A new name in the temporary folder, for a file written there by the server (an upload). */
    public Path newTemporaryFile() {
        return temporary.resolve("upload-" + UUID.randomUUID() + ".part");
    }

    /**
     * Takes over a file already written in the temporary folder, computing its SHA-256 and its
     * size by reading it once: no second copy on the disk.
     *
     * @throws TooLarge beyond {@code maxBytes}, the file deleted
     */
    public Received adopt(UUID organizationId, Path file, long maxBytes) throws IOException {
        try {
            if (!file.toAbsolutePath().normalize().getParent().equals(temporary.toAbsolutePath().normalize())) {
                throw new IOException("Not in the temporary folder: " + file);
            }
            if (Files.size(file) > maxBytes) {
                throw new TooLarge();
            }
            MessageDigest digest = sha256();
            byte[] head;
            long size;
            try (InputStream in = new DigestInputStream(Files.newInputStream(file), digest)) {
                head = in.readNBytes(HEAD);
                size = head.length;
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    size += read;
                }
            }
            if (size > maxBytes) {
                throw new TooLarge();
            }
            return new Received(organizationId, file, HexFormat.of().formatHex(digest.digest()), size, head);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(file);
            throw e;
        }
    }

    /** Moves a received file to its place, unless the same content is already there. */
    public void keep(Received received) throws IOException {
        Path target = path(received.organizationId(), received.sha256());
        if (Files.exists(target)) {
            Files.deleteIfExists(received.temporary());
            return;
        }
        Files.createDirectories(target.getParent());
        try {
            Files.move(received.temporary(), target, StandardCopyOption.ATOMIC_MOVE);
        } catch (FileAlreadyExistsException alreadyThere) {
            Files.deleteIfExists(received.temporary());
        } catch (AtomicMoveNotSupportedException e) {
            throw new IOException("LORE_FILES_PATH must be one file system with its tmp folder", e);
        }
    }

    /** Leaves a received file aside, for example when the import is refused. */
    public void discard(Received received) {
        try {
            Files.deleteIfExists(received.temporary());
        } catch (IOException e) {
            log.warn("Could not delete the temporary file {}: {}", received.temporary(), e.getMessage());
        }
    }

    /** Where a stored file is, whether it exists or not. */
    public Path path(UUID organizationId, String sha256) {
        if (!SHA256.matcher(sha256).matches()) {
            throw new IllegalArgumentException("Not a SHA-256: " + sha256);
        }
        Path path = root.resolve(organizationId.toString())
                .resolve(sha256.substring(0, 2))
                .resolve(sha256.substring(2, 4))
                .resolve(sha256)
                .normalize();
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException("Outside of the files folder: " + path);
        }
        return path;
    }

    /** True when the stored file is on the disk: a backup restored without its files lacks them. */
    public boolean exists(UUID organizationId, String sha256) {
        return Files.isRegularFile(path(organizationId, sha256));
    }

    /** Deletes a stored file, once no version uses it any more. */
    public void delete(UUID organizationId, String sha256) {
        try {
            Files.deleteIfExists(path(organizationId, sha256));
        } catch (IOException e) {
            log.warn("Could not delete the file {} of the organization {}: {}", sha256, organizationId, e.getMessage());
        }
    }

    /** The space left on the disk of the files. */
    public long freeSpace() {
        try {
            return Files.getFileStore(root).getUsableSpace();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The folder of the files, as shown to the administrators. */
    public Path root() {
        return root;
    }

    /** Where files are received before they move to their place. */
    public Path temporaryFolder() {
        return temporary;
    }

    /** The working folder of the extraction processes. */
    public Path extractionFolder() {
        return extraction;
    }

    /**
     * Checks at start that the folder exists, or can be made, and can be written to; deletes
     * what an earlier run left in {@code tmp/}.
     */
    private void prepare() {
        try {
            Files.createDirectories(extraction);
            Path probe = Files.createTempFile(temporary, "probe-", ".tmp");
            Files.delete(probe);
        } catch (IOException | SecurityException e) {
            throw new FilesPathException(root, e);
        }
        clean(temporary);
        clean(extraction);
    }

    /**
     * Deletes what was left there for an hour: a file being received or extracted changes every
     * few seconds, even for another instance of Lore that shares the folder.
     */
    private static void clean(Path folder) {
        java.nio.file.attribute.FileTime before = java.nio.file.attribute.FileTime.from(
                java.time.Instant.now().minus(LEFT_FOR));
        try (DirectoryStream<Path> files = Files.newDirectoryStream(folder, Files::isRegularFile)) {
            for (Path file : files) {
                if (Files.getLastModifiedTime(file).compareTo(before) < 0) {
                    Files.deleteIfExists(file);
                }
            }
        } catch (IOException e) {
            log.warn("Could not empty {}: {}", folder, e.getMessage());
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
