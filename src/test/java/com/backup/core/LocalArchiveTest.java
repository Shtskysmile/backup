package com.backup.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.io.IOException;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.backup.testing.FileSystemCapabilities.*;

/**
 * 本地归档往返与安全测试：硬链接保真、断链保留、软链接原样、
 * 还原目录中的符号链接/外部硬链接防护（内容绝不能写出还原目录）。
 * 硬链接保真用例依赖文件身份（fileKey），Windows 上按能力跳过。
 *
 * 用例清单（8 项）：
 * - missingHardlinkBaseDoesNotDeleteExistingTargetFile
 * - sameSizeSourceChangeCannotPublishAnUnrestorableArchive
 * - fullArchiveWithMissingPayloadIsNotReportedAsSuccessfulRestore
 * - zipPreservesRelativeAndBrokenSymlinks
 * - gzipPreservesHardlinkIdentity
 * - restoreRefusesExistingSymlinkFileWithoutWritingOutside
 * - restoreRefusesSymlinkParentWithoutWritingOutside
 * - replacingExistingHardlinkDoesNotChangeOtherPath
 */
class LocalArchiveTest {
    @TempDir Path temp;

    /** 增量只含硬链接条目而基文件缺失：还原报错，且绝不删除或覆盖还原目录里已有的同名文件。 */
    @Test void missingHardlinkBaseDoesNotDeleteExistingTargetFile() throws Exception {
        assumeHardLinks(temp);
        Path source = Files.createDirectory(temp.resolve("source"));
        Files.writeString(source.resolve("a"), "data");
        Files.createLink(source.resolve("b"), source.resolve("a"));
        assumeFileKeys(source.resolve("a"));
        java.util.List<BackupEntry> entries = new FileScanner().scan(source);
        BackupManifest manifest = new BackupManifest(source.toString(), java.time.Instant.now(), CompressionType.ZIP, true, false, entries);
        Path archive = temp.resolve("increment.zip");
        new com.backup.io.ArchiveService().writeZipArchive(source, archive, manifest, entries.stream().filter(e -> e.type() == EntryType.HARD_LINK).toList());
        Path out = Files.createDirectory(temp.resolve("out"));
        Files.writeString(out.resolve("b"), "keep");
        assertThrows(IOException.class, () -> new BackupService().restore(archive, out, null));
        assertEquals("keep", Files.readString(out.resolve("b")));
    }

    /** 扫描后源内容变化（同尺寸绕过尺寸检查）：SHA-256 不符，拒绝发布不可还原的归档。 */
    @Test void sameSizeSourceChangeCannotPublishAnUnrestorableArchive() throws Exception {
        Path source = Files.createDirectory(temp.resolve("source"));
        Path file = Files.writeString(source.resolve("file"), "before");
        java.util.List<BackupEntry> scanned = new FileScanner().scan(source);
        BackupManifest manifest = new BackupManifest(source.toString(), java.time.Instant.now(), CompressionType.ZIP, false, false, scanned);
        Files.writeString(file, "after!");
        assertThrows(IOException.class, () -> new com.backup.io.ArchiveService().writeZipArchive(source, temp.resolve("changed.zip"), manifest, scanned));
    }

    /** 全量归档缺载荷（清单有条目但包里没有）：还原必须报错而不是假成功。 */
    @Test void fullArchiveWithMissingPayloadIsNotReportedAsSuccessfulRestore() throws Exception {
        Path source = Files.createDirectory(temp.resolve("source"));
        Files.writeString(source.resolve("file"), "content");
        java.util.List<BackupEntry> scanned = new FileScanner().scan(source);
        BackupManifest manifest = new BackupManifest(source.toString(), java.time.Instant.now(), CompressionType.ZIP, false, false, scanned);
        Path archive = temp.resolve("missing.zip");
        new com.backup.io.ArchiveService().writeZipArchive(source, archive, manifest, java.util.List.of());
        assertThrows(IOException.class, () -> new BackupService().restore(archive, temp.resolve("out"), null));
    }

    /** 便捷备份：无加密、无筛选、目标为本测试的 backups 目录。 */
    private BackupResult backup(Path source, CompressionType type) throws IOException {
        return new BackupService().backup(new BackupOptions(source, temp.resolve("backups"), "local", type, false, false, null));
    }

    /** ZIP 往返：相对软链接与断链都按目标字符串原样重建（不解析、不报错）。 */
    @Test void zipPreservesRelativeAndBrokenSymlinks() throws Exception {
        assumeSymbolicLinks(temp);
        Path source = Files.createDirectory(temp.resolve("source"));
        Files.writeString(source.resolve("file"), "content");
        Files.createSymbolicLink(source.resolve("link"), Path.of("file"));
        Files.createSymbolicLink(source.resolve("broken"), Path.of("missing"));
        Path out = temp.resolve("out");
        new BackupService().restore(backup(source, CompressionType.ZIP).archivePath(), out, null);
        assertTrue(Files.isSymbolicLink(out.resolve("link")));
        assertEquals(Path.of("file"), Files.readSymbolicLink(out.resolve("link")));
        assertEquals(Path.of("missing"), Files.readSymbolicLink(out.resolve("broken")));
    }

    /** GZIP 往返：硬链接对还原后仍指向同一 inode（isSameFile 验证）。 */
    @Test void gzipPreservesHardlinkIdentity() throws Exception {
        assumeHardLinks(temp);
        Path source = Files.createDirectory(temp.resolve("source"));
        Files.writeString(source.resolve("first"), "shared");
        Files.createLink(source.resolve("second"), source.resolve("first"));
        assumeFileKeys(source.resolve("first"));
        Path out = temp.resolve("out");
        new BackupService().restore(backup(source, CompressionType.GZIP).archivePath(), out, null);
        assertTrue(Files.isSameFile(out.resolve("first"), out.resolve("second")));
    }

    /** 还原目标位置已有指向外部的符号链接：拒绝覆盖，外部文件内容分毫不动。 */
    @Test void restoreRefusesExistingSymlinkFileWithoutWritingOutside() throws Exception {
        assumeSymbolicLinks(temp);
        Path source = Files.createDirectory(temp.resolve("source"));
        Files.writeString(source.resolve("file"), "new");
        Path archive = backup(source, CompressionType.ZIP).archivePath();
        Path outside = Files.writeString(temp.resolve("outside"), "unchanged");
        Path out = Files.createDirectory(temp.resolve("out"));
        Files.createSymbolicLink(out.resolve("file"), outside);
        assertThrows(IOException.class, () -> new BackupService().restore(archive, out, null));
        assertEquals("unchanged", Files.readString(outside));
    }

    /** 还原路径中间某级是指向外部目录的符号链接：拒绝写入，外部目录不出现任何文件。 */
    @Test void restoreRefusesSymlinkParentWithoutWritingOutside() throws Exception {
        assumeDirectorySymbolicLinks(temp);
        Path source = Files.createDirectories(temp.resolve("source/docs"));
        Files.writeString(source.resolve("file"), "new");
        Path archive = backup(source.getParent(), CompressionType.GZIP).archivePath();
        Path outside = Files.createDirectory(temp.resolve("outside"));
        Path out = Files.createDirectory(temp.resolve("out"));
        Files.createSymbolicLink(out.resolve("docs"), outside);
        assertThrows(IOException.class, () -> new BackupService().restore(archive, out, null));
        assertFalse(Files.exists(outside.resolve("file")));
    }

    /** 原子替换验证：覆盖还原目录中与外部文件互为硬链接的名字，外部硬链接另一端内容不变。 */
    @Test void replacingExistingHardlinkDoesNotChangeOtherPath() throws Exception {
        assumeHardLinks(temp);
        Path source = Files.createDirectory(temp.resolve("source"));
        Files.writeString(source.resolve("file"), "new");
        Path archive = backup(source, CompressionType.ZIP).archivePath();
        Path out = Files.createDirectory(temp.resolve("out"));
        Path outside = Files.writeString(temp.resolve("outside"), "old");
        Files.createLink(out.resolve("file"), outside);
        new BackupService().restore(archive, out, null);
        assertEquals("old", Files.readString(outside));
        // 外部哨兵：原硬链接另一端的内容必须原封不动。
        assertEquals("new", Files.readString(out.resolve("file")));
    }
}
