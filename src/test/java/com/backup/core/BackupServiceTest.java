package com.backup.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackupServiceTest {
    @TempDir
    Path tempDirectory;

    @Test
    void zipBackupRestoresFilesAndMetadata() throws IOException {
        Path source = Files.createDirectory(tempDirectory.resolve("source"));
        Path destination = Files.createDirectory(tempDirectory.resolve("backups"));
        Path restored = tempDirectory.resolve("restored");
        Files.createDirectories(source.resolve("docs"));
        Files.writeString(source.resolve("docs/readme.txt"), "hello");

        BackupService service = new BackupService();
        BackupResult result = service.backup(new BackupOptions(
                source, destination, "test", CompressionType.ZIP, false, false, new char[0]
        ));
        BackupManifest manifest = service.restore(result.archivePath(), restored, new char[0]);

        assertEquals(2, manifest.entries().size());
        assertEquals("hello", Files.readString(restored.resolve("docs/readme.txt")));
        assertTrue(Files.isRegularFile(result.manifestPath()));
    }

    @Test
    void encryptedGzipBackupRequiresTheCorrectPassphrase() throws IOException {
        Path source = Files.createDirectory(tempDirectory.resolve("source"));
        Path destination = Files.createDirectory(tempDirectory.resolve("backups"));
        Files.writeString(source.resolve("secret.txt"), "private");

        BackupService service = new BackupService();
        BackupResult result = service.backup(new BackupOptions(
                source, destination, "secret", CompressionType.GZIP, false, true, "passphrase".toCharArray()
        ));

        assertThrows(IOException.class, () ->
                service.restore(result.archivePath(), tempDirectory.resolve("wrong"), "bad".toCharArray()));
        Path restored = tempDirectory.resolve("restored");
        service.restore(result.archivePath(), restored, "passphrase".toCharArray());
        assertEquals("private", Files.readString(restored.resolve("secret.txt")));
    }

    @Test
    void incrementalBackupContainsOnlyChangedFilesAndDirectories() throws IOException {
        Path source = Files.createDirectory(tempDirectory.resolve("source"));
        Path destination = Files.createDirectory(tempDirectory.resolve("backups"));
        Files.createDirectories(source.resolve("docs"));
        Files.writeString(source.resolve("docs/unchanged.txt"), "same");
        Files.writeString(source.resolve("changed.txt"), "before");

        BackupService service = new BackupService();
        service.backup(new BackupOptions(source, destination, "test", CompressionType.ZIP, false, false, new char[0]));
        Files.writeString(source.resolve("changed.txt"), "after");
        BackupResult result = service.backup(new BackupOptions(
                source, destination, "test", CompressionType.ZIP, true, false, new char[0]
        ));

        List<String> entries;
        try (ZipInputStream zipInputStream = new ZipInputStream(Files.newInputStream(result.archivePath()))) {
            entries = new ArrayList<>();
            java.util.zip.ZipEntry entry;
            while ((entry = zipInputStream.getNextEntry()) != null) {
                entries.add(entry.getName());
            }
        }
        assertTrue(entries.contains("BACKPACK-MANIFEST.properties"));
        assertTrue(entries.contains("data/changed.txt"));
        assertTrue(entries.contains("data/docs/"));
        assertFalse(entries.contains("data/docs/unchanged.txt"));
    }

    @Test
    void destinationCannotBeInsideSource() throws IOException {
        Path source = Files.createDirectory(tempDirectory.resolve("source"));
        Path destination = Files.createDirectory(source.resolve("backups"));
        BackupService service = new BackupService();

        assertThrows(IOException.class, () -> service.backup(new BackupOptions(
                source, destination, "unsafe", CompressionType.ZIP, false, false, new char[0]
        )));
    }
}
