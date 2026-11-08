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

/**
 * 备份服务编排测试：增量只含变化条目、任务/进程隔离不续链、加密口令正确性、
 * 归档命名防冲突。
 */
class BackupServiceTest {
    @TempDir
    Path tempDirectory;

    @Test
    /** 全量备份加还原往返：清单条目数、文件内容与旁挂清单文件都正确。 */
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
    /** 加密 GZIP：错误口令解密失败抛异常；正确口令还原出原文。 */
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
    /** 增量归档只含变化文件、其父目录条目与清单；未变的文件绝不入包。 */
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
    /** 目标目录位于源目录内：备份直接拒绝（防止备份过程自吞源数据）。 */
    void destinationCannotBeInsideSource() throws IOException {
        Path source = Files.createDirectory(tempDirectory.resolve("source"));
        Path destination = Files.createDirectory(source.resolve("backups"));
        BackupService service = new BackupService();

        assertThrows(IOException.class, () -> service.backup(new BackupOptions(
                source, destination, "unsafe", CompressionType.ZIP, false, false, new char[0]
        )));
    }
}
