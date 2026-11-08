package com.backup.io;

import com.backup.core.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.IOException;
import java.nio.file.attribute.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static com.backup.testing.FileSystemCapabilities.*;

/**
 * 软链接属性回归测试：链接自身属性先比对后恢复（一致不动），
 * macOS chmod -h 生效路径与平台不支持路径的警告行为，外部目标零影响。
 *
 * 用例清单（6 项）：
 * - alreadyMatchingLinkAttributesDoNotReportSpuriousWarnings
 * - differingMacLinkPermissionsAreRestoredWithoutChangingTarget
 * - differingBrokenLinkPermissionsCanBeRestoredWithoutCreatingTarget
 * - directoryLinkPermissionRestorationDoesNotChangeDirectoryTarget
 * - failedNoFollowPermissionCommandLeavesTargetUntouchedAndReportsReason
 * - unknownLinkOwnerProducesSpecificFailureRatherThanBlanketSkip
 */
class SymbolicLinkMetadataTest {
    @TempDir Path temp;

    private BackupResult backup(Path source) throws Exception {
        return new BackupService().backup(new BackupOptions(source, temp.resolve("backups"), "links", CompressionType.ZIP, false, false, null));
    }

    /** 链接属性已与清单一致：还原零警告（不把"无需修改"误报为"跳过"），且各链接属性核对无误。 */
    @Test void alreadyMatchingLinkAttributesDoNotReportSpuriousWarnings() throws Exception {
        assumeSymbolicLinks(temp); assumeDirectorySymbolicLinks(temp); assumePosix(temp);
        Path outside = Files.writeString(temp.resolve("outside"), "sentinel");
        Path directory = Files.createDirectory(temp.resolve("outside-directory"));
        Path source = Files.createDirectory(temp.resolve("source"));
        Files.createSymbolicLink(source.resolve("absolute"), outside);
        Files.createSymbolicLink(source.resolve("relative"), Path.of("../outside"));
        Files.createSymbolicLink(source.resolve("broken"), Path.of("missing"));
        Files.createSymbolicLink(source.resolve("directory"), directory);
        BackupResult archive = backup(source);
        RestoreResult restored = new BackupService().restoreDetailed(archive.archivePath(), temp.resolve("out"), null);
        assertEquals(List.of(), restored.warnings(), "Attributes already matching the manifest must not be reported as skipped");
        for (BackupEntry entry : restored.manifest().entries()) {
            PosixFileAttributes actual = Files.readAttributes(temp.resolve("out").resolve(entry.relativePath()), PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            assertEquals(entry.permissions(), PosixFilePermissions.toString(actual.permissions()));
            assertEquals(entry.owner(), actual.owner().getName()); assertEquals(entry.group(), actual.group().getName());
        }
    }

    private void macLinkMode(Path link, String mode) throws Exception {
        assumeTrue(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac"), "Mutable symlink permissions require macOS lchmod");
        Process chmod = new ProcessBuilder("/bin/chmod", "-h", mode, link.toString()).start();
        assertTrue(chmod.waitFor(5, TimeUnit.SECONDS)); assertEquals(0, chmod.exitValue());
    }

    /** 链接权限与目标不同（macOS chmod -h 路径）：链接自身权限被恢复，外部目标的权限/时间/内容分毫不动。 */
    @Test void differingMacLinkPermissionsAreRestoredWithoutChangingTarget() throws Exception {
        assumeSymbolicLinks(temp); assumePosix(temp);
        Path outside = Files.writeString(temp.resolve("outside"), "sentinel");
        Files.setPosixFilePermissions(outside, PosixFilePermissions.fromString("rw-r-----"));
        BasicFileAttributes before = Files.readAttributes(outside, BasicFileAttributes.class);
        Path source = Files.createDirectory(temp.resolve("source"));
        Path link = Files.createSymbolicLink(source.resolve("link"), outside);
        macLinkMode(link, "600");
        BackupResult archive = backup(source);
        RestoreResult restored = new BackupService().restoreDetailed(archive.archivePath(), temp.resolve("out"), null);
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(temp.resolve("out/link"), LinkOption.NOFOLLOW_LINKS)));
        assertEquals(List.of(), restored.warnings());
        BasicFileAttributes after = Files.readAttributes(outside, BasicFileAttributes.class);
        assertEquals(before.lastModifiedTime(), after.lastModifiedTime()); assertEquals(before.lastAccessTime(), after.lastAccessTime());
        assertEquals("rw-r-----", PosixFilePermissions.toString(Files.getPosixFilePermissions(outside)));
        assertEquals("sentinel", Files.readString(outside));
    }

    /** 断链的权限恢复：不需要目标存在即可修改链接自身权限（lchmod 语义），目标始终不被创建。 */
    @Test void differingBrokenLinkPermissionsCanBeRestoredWithoutCreatingTarget() throws Exception {
        assumeSymbolicLinks(temp); assumePosix(temp);
        Path source = Files.createDirectory(temp.resolve("source"));
        Path link = Files.createSymbolicLink(source.resolve("link"), Path.of("missing"));
        macLinkMode(link, "640");
        RestoreResult restored = new BackupService().restoreDetailed(backup(source).archivePath(), temp.resolve("out"), null);
        assertEquals("rw-r-----", PosixFilePermissions.toString(Files.getPosixFilePermissions(temp.resolve("out/link"), LinkOption.NOFOLLOW_LINKS)));
        assertFalse(Files.exists(temp.resolve("out/missing"), LinkOption.NOFOLLOW_LINKS));
        assertEquals(List.of(), restored.warnings());
    }

    /** 指向目录的链接：chmod -h 只改链接本身，目录目标的权限/内容/时间不受影响。 */
    @Test void directoryLinkPermissionRestorationDoesNotChangeDirectoryTarget() throws Exception {
        assumeDirectorySymbolicLinks(temp); assumePosix(temp);
        Path outside = Files.createDirectory(temp.resolve("outside"));
        Files.setPosixFilePermissions(outside, PosixFilePermissions.fromString("rwx------"));
        Files.writeString(outside.resolve("sentinel"), "keep");
        BasicFileAttributes before = Files.readAttributes(outside, BasicFileAttributes.class);
        Path source = Files.createDirectory(temp.resolve("source"));
        Path link = Files.createSymbolicLink(source.resolve("directory-link"), outside);
        macLinkMode(link, "640");
        RestoreResult restored = new BackupService().restoreDetailed(backup(source).archivePath(), temp.resolve("out"), null);
        assertEquals("rw-r-----", PosixFilePermissions.toString(Files.getPosixFilePermissions(temp.resolve("out/directory-link"), LinkOption.NOFOLLOW_LINKS)));
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(outside)));
        BasicFileAttributes after = Files.readAttributes(outside, BasicFileAttributes.class);
        assertEquals(before.lastModifiedTime(), after.lastModifiedTime()); assertEquals(before.lastAccessTime(), after.lastAccessTime());
        assertEquals("keep", Files.readString(outside.resolve("sentinel")));
        assertEquals(List.of(), restored.warnings());
    }

    /** chmod -h 失败（exit 17）：抛出带回原因的异常；外部目标权限/内容不变，链接仍在。 */
    @Test void failedNoFollowPermissionCommandLeavesTargetUntouchedAndReportsReason() throws Exception {
        assumeSymbolicLinks(temp); assumePosix(temp);
        Path outside = Files.writeString(temp.resolve("outside"), "keep");
        Files.setPosixFilePermissions(outside, PosixFilePermissions.fromString("rw-r-----"));
        Path link = Files.createSymbolicLink(temp.resolve("link"), outside);
        Path fake = Files.writeString(temp.resolve("failed-chmod"), "#!/bin/sh\necho 'permission denied in test' >&2\nexit 17\n");
        Files.setPosixFilePermissions(fake, PosixFilePermissions.fromString("rwx------"));
        IOException failure = assertThrows(IOException.class, () -> new SymbolicLinkPermissions(fake.toString(), true)
                .restore(link, PosixFilePermissions.fromString("---------")));
        assertTrue(failure.getMessage().contains("17") && failure.getMessage().contains("permission denied in test"), failure.getMessage());
        assertEquals("rw-r-----", PosixFilePermissions.toString(Files.getPosixFilePermissions(outside)));
        assertEquals("keep", Files.readString(outside));
        assertTrue(Files.isSymbolicLink(link));
    }

    /** 清单属主不存在：产出针对该链接的属主失败警告，而不是一刀切跳过所有元数据恢复。 */
    @Test void unknownLinkOwnerProducesSpecificFailureRatherThanBlanketSkip() throws Exception {
        assumeSymbolicLinks(temp); assumePosix(temp);
        Path source = Files.createDirectory(temp.resolve("source"));
        Files.createSymbolicLink(source.resolve("link"), Path.of("missing"));
        BackupEntry scanned = new FileScanner().scan(source).getFirst();
        BackupEntry unknown = new BackupEntry(scanned.relativePath(), false, scanned.size(), scanned.lastModifiedMillis(), "",
                scanned.permissions(), "missing_backup_user_01a199ed", scanned.group(), EntryType.SYMBOLIC_LINK, scanned.linkTarget(), null, null);
        BackupManifest manifest = new BackupManifest(source.toString(), Instant.now(), CompressionType.ZIP, false, false, List.of(unknown));
        Path archive = temp.resolve("unknown-owner.zip"); new ArchiveService().writeZipArchive(source, archive, manifest, List.of(unknown));
        RestoreResult restored = new ArchiveService().restoreDetailed(archive, temp.resolve("out"));
        assertTrue(restored.warnings().stream().anyMatch(w -> w.contains("link") && w.contains("could not restore") && w.contains("owner")), restored.warnings().toString());
        assertFalse(restored.warnings().stream().anyMatch(w -> w.contains("skipped to protect")));
        assertTrue(Files.isSymbolicLink(temp.resolve("out/link")));
    }
}
