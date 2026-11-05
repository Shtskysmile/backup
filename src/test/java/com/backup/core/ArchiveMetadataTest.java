package com.backup.core;

import com.backup.io.ArchiveService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static com.backup.testing.FileSystemCapabilities.*;

class ArchiveMetadataTest {
    @TempDir Path temp;
    private static final long OLD = 1_600_000_000_000L;

    @Test void accessTimeOnlyChangeIsRestoredWithoutRearchivingUnchangedContent() throws Exception {
        Path source = Files.createDirectory(temp.resolve("source"));
        Path file = Files.writeString(source.resolve("file"), "unchanged");
        times(file, OLD, OLD - 100_000);
        BackupService service = new BackupService();
        BackupOptions options = options(source, true);
        BackupResult full = service.backup(options);
        times(file, OLD, OLD - 200_000);
        BackupResult incremental = service.backup(options);
        assertEquals(0, incremental.archivedEntries());
        Path out = temp.resolve("out");
        service.restore(full.archivePath(), out, null);
        service.restore(incremental.archivePath(), out, null);
        assertEquals(OLD - 200_000, attrs(out.resolve("file")).lastAccessTime().toMillis());
    }

    @Test void incrementalMetadataDoesNotModifyUnrelatedBaselineContent() throws Exception {
        Path source = Files.createDirectory(temp.resolve("source"));
        Path file = Files.writeString(source.resolve("file"), "snapshot");
        BackupService service = new BackupService();
        BackupOptions options = options(source, true);
        service.backup(options);
        times(file, OLD, OLD);
        service.backup(options);
        BackupResult incremental = service.backup(options);
        Path out = Files.createDirectory(temp.resolve("out"));
        Path unrelated = Files.writeString(out.resolve("file"), "different");
        times(unrelated, OLD - 500_000, OLD - 600_000);
        RestoreResult result = service.restoreDetailed(incremental.archivePath(), out, null);
        assertEquals(OLD - 500_000, attrs(unrelated).lastModifiedTime().toMillis());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("file") && w.contains("baseline")));
        assertEquals("different", Files.readString(unrelated));
    }

    private BackupOptions options(Path source, boolean incremental) {
        return new BackupOptions(source, temp.resolve("backups"), "metadata", CompressionType.ZIP, incremental, false, null);
    }
    private void times(Path path, long modified, long access) throws Exception {
        Files.getFileAttributeView(path, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
                .setTimes(FileTime.fromMillis(modified), FileTime.fromMillis(access), null);
    }
    private BasicFileAttributes attrs(Path path) throws Exception {
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }

    @Test void scannerRecordsAccessTimeBeforeReadingFileForHash() throws Exception {
        Path source = Files.createDirectory(temp.resolve("source"));
        Path file = Files.writeString(source.resolve("file"), "hash this content");
        times(file, OLD, OLD - 100_000);
        long before = attrs(file).lastAccessTime().toMillis();
        BackupEntry entry = new FileScanner().scan(source).getFirst();
        assertEquals(before, entry.lastAccessMillis().longValue());
        assertEquals(OLD, entry.lastModifiedMillis());
        assertEquals(HashUtils.sha256(file), entry.sha256());
    }

    @Test void restorePreservesFileAccessModifiedTimesAndPermissions() throws Exception {
        Path source = Files.createDirectory(temp.resolve("source"));
        Path file = Files.writeString(source.resolve("file"), "metadata"); assumePosix(file);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r-----"));
        times(file, OLD, OLD - 100_000);
        BackupService service = new BackupService();
        BackupResult backup = service.backup(options(source, false));
        Path out = temp.resolve("out"); service.restoreDetailed(backup.archivePath(), out, null);
        BasicFileAttributes attributes = attrs(out.resolve("file"));
        assertEquals(OLD, attributes.lastModifiedTime().toMillis());
        assertEquals(OLD - 100_000, attributes.lastAccessTime().toMillis());
        assertEquals("rw-r-----", PosixFilePermissions.toString(Files.getPosixFilePermissions(out.resolve("file"))));
        assertEquals("metadata", Files.readString(out.resolve("file")));
    }

    @Test void directoryMetadataIsAppliedAfterChildrenFromDeepestToShallowest() throws Exception {
        Path source = Files.createDirectory(temp.resolve("source"));
        Path parent = Files.createDirectory(source.resolve("parent"));
        Path child = Files.createDirectory(parent.resolve("child"));
        Files.writeString(child.resolve("file"), "child"); assumePosix(parent);
        Files.setPosixFilePermissions(parent, PosixFilePermissions.fromString("rwxr-x---"));
        Files.setPosixFilePermissions(child, PosixFilePermissions.fromString("rwx------"));
        times(child, OLD + 20_000, OLD - 20_000); times(parent, OLD, OLD - 40_000);
        BackupService service = new BackupService();
        BackupResult archive = service.backup(options(source, false));
        Path out = temp.resolve("out"); service.restore(archive.archivePath(), out, null);
        assertEquals(OLD, attrs(out.resolve("parent")).lastModifiedTime().toMillis());
        assertEquals(OLD + 20_000, attrs(out.resolve("parent/child")).lastModifiedTime().toMillis());
        assertEquals("rwxr-x---", PosixFilePermissions.toString(Files.getPosixFilePermissions(out.resolve("parent"))));
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(out.resolve("parent/child"))));
        assertEquals("child", Files.readString(out.resolve("parent/child/file")));
    }

    @Test void unknownOwnerProducesPathSpecificWarningWhileRestoringData() throws Exception {
        Path source = Files.createDirectory(temp.resolve("source"));
        Path file = Files.writeString(source.resolve("file"), "owned");
        BackupEntry entry = new BackupEntry("file", false, Files.size(file), OLD, HashUtils.sha256(file), "",
                "codex_missing_owner_01a199ed", "");
        BackupManifest manifest = new BackupManifest(source.toString(), Instant.now(), CompressionType.ZIP, false, false, List.of(entry));
        ArchiveService archives = new ArchiveService(); Path archive = temp.resolve("owner.zip");
        archives.writeZipArchive(source, archive, manifest, List.of(entry));
        RestoreResult result = archives.restoreDetailed(archive, temp.resolve("out"));
        assertEquals("owned", Files.readString(temp.resolve("out/file")));
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("file") && w.contains("owner")), result.warnings().toString());
    }

    @Test void absoluteAndBrokenSymlinksPreserveTargetsWithoutChangingExternalContentOrMetadata() throws Exception {
        assumeSymbolicLinks(temp);
        Path outside = Files.writeString(temp.resolve("outside"), "external sentinel"); assumePosix(outside);
        Files.setPosixFilePermissions(outside, PosixFilePermissions.fromString("rw-r-----"));
        times(outside, OLD, OLD - 100_000);
        BasicFileAttributes before = attrs(outside);
        Path source = Files.createDirectory(temp.resolve("source"));
        Files.createSymbolicLink(source.resolve("absolute"), outside);
        Files.createSymbolicLink(source.resolve("broken"), temp.resolve("absent"));
        times(source.resolve("absolute"), OLD + 100_000, OLD + 200_000);
        BackupService service = new BackupService();
        BackupResult backup = service.backup(options(source, false));
        BackupManifest manifest = BackupManifest.load(backup.manifestPath());
        assertTrue(manifest.entries().stream().allMatch(e -> e.type() == EntryType.SYMBOLIC_LINK));
        assertEquals("rw-r-----", PosixFilePermissions.toString(Files.getPosixFilePermissions(outside)), "Backup must not change the external target");
        Path out = temp.resolve("out"); service.restore(backup.archivePath(), out, null);
        assertEquals(outside, Files.readSymbolicLink(out.resolve("absolute")));
        assertEquals(temp.resolve("absent"), Files.readSymbolicLink(out.resolve("broken")));
        BasicFileAttributes after = attrs(outside);
        assertEquals(before.lastModifiedTime(), after.lastModifiedTime());
        assertEquals(before.lastAccessTime(), after.lastAccessTime());
        assertEquals("rw-r-----", PosixFilePermissions.toString(Files.getPosixFilePermissions(outside)));
        assertEquals("external sentinel", Files.readString(outside));
    }

    @Test void incrementalRestoreSplitsFormerHardlinksWithoutChangingUnmodifiedSibling() throws Exception {
        assumeHardLinks(temp);
        Path source = Files.createDirectory(temp.resolve("source"));
        Path first = Files.writeString(source.resolve("a"), "original");
        Files.createLink(source.resolve("b"), first);
        assumeFileKeys(first);
        BackupService service = new BackupService();
        BackupOptions options = options(source, true);
        BackupResult full = service.backup(options);
        Files.delete(source.resolve("b")); Files.writeString(source.resolve("b"), "replacement");
        BackupResult increment = service.backup(options);
        assertTrue(BackupManifest.load(increment.manifestPath()).incremental());
        Path out = temp.resolve("out"); service.restore(full.archivePath(), out, null);
        assertTrue(Files.isSameFile(out.resolve("a"), out.resolve("b")));
        service.restore(increment.archivePath(), out, null);
        assertFalse(Files.isSameFile(out.resolve("a"), out.resolve("b")));
        assertEquals("original", Files.readString(out.resolve("a")));
        assertEquals("replacement", Files.readString(out.resolve("b")));
    }

    @Test void symlinkMetadataOnlyChangeIsIncludedInIncrementAndRestored() throws Exception {
        assumeSymbolicLinks(temp);
        Path source = Files.createDirectory(temp.resolve("source"));
        Path link = Files.createSymbolicLink(source.resolve("link"), Path.of("missing"));
        times(link, OLD, OLD);
        BackupService service = new BackupService(); BackupOptions options = options(source, true);
        BackupResult full = service.backup(options);
        times(link, OLD + 100_000, OLD);
        BackupResult increment = service.backup(options);
        assertTrue(BackupManifest.load(increment.manifestPath()).incremental());
        assertEquals(1, increment.archivedEntries());
        Path out = temp.resolve("out"); service.restore(full.archivePath(), out, null); service.restore(increment.archivePath(), out, null);
        assertEquals(Path.of("missing"), Files.readSymbolicLink(out.resolve("link")));
        assertEquals(OLD + 100_000, attrs(out.resolve("link")).lastModifiedTime().toMillis());
    }

    @Test void hardlinkPermissionOnlyChangeIsIncludedInIncrementAndKeepsIdentity() throws Exception {
        assumeHardLinks(temp);
        Path source = Files.createDirectory(temp.resolve("source"));
        Path first = Files.writeString(source.resolve("a"), "unchanged data"); assumePosix(first);
        Files.createLink(source.resolve("b"), first);
        assumeFileKeys(first);
        Files.setPosixFilePermissions(first, PosixFilePermissions.fromString("rw-------"));
        BackupService service = new BackupService(); BackupOptions options = options(source, true);
        BackupResult full = service.backup(options);
        Files.setPosixFilePermissions(first, PosixFilePermissions.fromString("rw-r-----"));
        BackupResult increment = service.backup(options);
        assertTrue(BackupManifest.load(increment.manifestPath()).incremental());
        assertEquals(2, increment.archivedEntries());
        Path out = temp.resolve("out"); service.restore(full.archivePath(), out, null); service.restore(increment.archivePath(), out, null);
        assertTrue(Files.isSameFile(out.resolve("a"), out.resolve("b")));
        assertEquals("rw-r-----", PosixFilePermissions.toString(Files.getPosixFilePermissions(out.resolve("b"))));
        assertEquals("unchanged data", Files.readString(out.resolve("a")));
    }

}
