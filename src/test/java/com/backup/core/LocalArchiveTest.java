package com.backup.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.io.IOException;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.backup.testing.FileSystemCapabilities.*;

class LocalArchiveTest {
    @TempDir Path temp;

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

    @Test void sameSizeSourceChangeCannotPublishAnUnrestorableArchive() throws Exception {
        Path source = Files.createDirectory(temp.resolve("source"));
        Path file = Files.writeString(source.resolve("file"), "before");
        java.util.List<BackupEntry> scanned = new FileScanner().scan(source);
        BackupManifest manifest = new BackupManifest(source.toString(), java.time.Instant.now(), CompressionType.ZIP, false, false, scanned);
        Files.writeString(file, "after!");
        assertThrows(IOException.class, () -> new com.backup.io.ArchiveService().writeZipArchive(source, temp.resolve("changed.zip"), manifest, scanned));
    }

    @Test void fullArchiveWithMissingPayloadIsNotReportedAsSuccessfulRestore() throws Exception {
        Path source = Files.createDirectory(temp.resolve("source"));
        Files.writeString(source.resolve("file"), "content");
        java.util.List<BackupEntry> scanned = new FileScanner().scan(source);
        BackupManifest manifest = new BackupManifest(source.toString(), java.time.Instant.now(), CompressionType.ZIP, false, false, scanned);
        Path archive = temp.resolve("missing.zip");
        new com.backup.io.ArchiveService().writeZipArchive(source, archive, manifest, java.util.List.of());
        assertThrows(IOException.class, () -> new BackupService().restore(archive, temp.resolve("out"), null));
    }

    private BackupResult backup(Path source, CompressionType type) throws IOException {
        return new BackupService().backup(new BackupOptions(source, temp.resolve("backups"), "local", type, false, false, null));
    }

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
        assertEquals("new", Files.readString(out.resolve("file")));
    }
}
