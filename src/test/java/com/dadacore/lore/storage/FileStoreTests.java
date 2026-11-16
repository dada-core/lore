package com.dadacore.lore.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

import com.dadacore.lore.LoreProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

/** The files on disk: received in tmp/, kept once under their SHA-256, never outside the folder. */
class FileStoreTests {

    private static final UUID ORGANIZATION = UUID.fromString("0193b1f2-0000-7000-8000-000000000001");
    private static final byte[] CONTENT = "Procédure de restauration".getBytes(StandardCharsets.UTF_8);
    private static final String SHA256 = "8d5d8f3a2c1b0b9e7f6a5d4c3b2a1908f7e6d5c4b3a291807f6e5d4c3b2a1908";

    @TempDir
    Path folder;

    @Test
    void aFileIsReceivedThenKeptOnceUnderItsContent() throws Exception {
        FileStore store = store(folder);

        FileStore.Received first = store.receive(ORGANIZATION, new ByteArrayInputStream(CONTENT), 1024);
        assertThat(first.size()).isEqualTo(CONTENT.length);
        assertThat(first.sha256()).hasSize(64);
        assertThat(first.head()).isEqualTo(CONTENT);
        assertThat(first.temporary()).startsWith(folder.resolve("tmp"));
        store.keep(first);

        Path kept = store.path(ORGANIZATION, first.sha256());
        assertThat(kept).hasBinaryContent(CONTENT);
        assertThat(kept.getParent().getParent().getParent()).isEqualTo(folder.resolve(ORGANIZATION.toString()));

        FileStore.Received second = store.receive(ORGANIZATION, new ByteArrayInputStream(CONTENT), 1024);
        store.keep(second);
        assertThat(second.temporary()).doesNotExist();
        try (var files = Files.walk(folder.resolve(ORGANIZATION.toString()))) {
            assertThat(files.filter(Files::isRegularFile)).hasSize(1);
        }

        store.delete(ORGANIZATION, first.sha256());
        assertThat(kept).doesNotExist();
    }

    @Test
    void aFileBeyondTheLimitLeavesNothing() throws Exception {
        FileStore store = store(folder);

        assertThatThrownBy(() -> store.receive(ORGANIZATION, new ByteArrayInputStream(CONTENT), 10))
                .isInstanceOf(FileStore.TooLarge.class);
        try (var files = Files.list(folder.resolve("tmp"))) {
            assertThat(files.filter(Files::isRegularFile)).isEmpty();
        }
    }

    @Test
    void aPathIsOnlyMadeOfAnOrganizationAndAContent() {
        FileStore store = store(folder);

        assertThat(store.path(ORGANIZATION, SHA256)).startsWithRaw(folder.toAbsolutePath().normalize());
        assertThatThrownBy(() -> store.path(ORGANIZATION, "../../etc/passwd")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.path(ORGANIZATION, SHA256.toUpperCase())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void whatAnEarlierRunLeftInTmpIsDeletedAtStartButNotWhatIsBeingReceived() throws Exception {
        store(folder);
        Path leftover = Files.writeString(folder.resolve("tmp").resolve("upload-1.part"), "half");
        Files.setLastModifiedTime(leftover, java.nio.file.attribute.FileTime.from(
                java.time.Instant.now().minus(java.time.Duration.ofHours(2))));
        // Received right now, by another instance of Lore that shares the folder.
        Path receiving = Files.writeString(folder.resolve("tmp").resolve("upload-2.part"), "in progress");

        store(folder);

        assertThat(leftover).doesNotExist();
        assertThat(receiving).exists();
    }

    @Test
    void aFolderThatCannotBeUsedStopsLoreWithItsName() throws Exception {
        Path file = Files.writeString(folder.resolve("not-a-folder"), "x");

        assertThatThrownBy(() -> store(file))
                .isInstanceOf(FilesPathException.class)
                .satisfies(e -> assertThat(new FilesPathFailureAnalyzer().analyze(e).getAction()).contains("LORE_FILES_PATH"));
    }

    private static FileStore store(Path root) {
        return new FileStore(new LoreProperties(URI.create("http://localhost"), Duration.ofHours(8), null, root,
                DataSize.ofGigabytes(1), 2, Duration.ofSeconds(120), DataSize.ofMegabytes(512)));
    }
}
