package com.backup.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LocalBackupServiceTest {
    @TempDir Path temp;
    private BackupOptions options(Path source, String name, boolean incremental, int keep, BackupFilter filter) {
        return new BackupOptions(source, temp.resolve("backups"), name, CompressionType.ZIP, incremental, false, null, filter, keep);
    }

    @Test void filtersBeforeChoosingHardlinkPayloadAndKeepsAncestors() throws Exception {
        Path source = Files.createDirectories(temp.resolve("source/docs"));
        Files.writeString(source.resolve("a.txt"), "shared");
        Files.createLink(source.resolve("b.txt"), source.resolve("a.txt"));
        Files.writeString(source.resolve("ignored.pdf"), "no");
        Files.createDirectories(source.resolve("empty"));
        BackupFilter filter = new BackupFilter("docs/b.txt", "", "txt", false, "", null, null, null, null);
        BackupService service = new BackupService();
        BackupResult result = service.backup(options(source.getParent(), "filter", false, 0, filter));
        Path out = temp.resolve("out");
        service.restore(result.archivePath(), out, null);
        assertEquals("shared", Files.readString(out.resolve("docs/b.txt")));
        assertFalse(Files.exists(out.resolve("docs/a.txt")));
        assertFalse(Files.exists(out.resolve("docs/empty")));
        assertEquals(2, BackupManifest.load(result.manifestPath()).entries().size());
    }

    @Test void changingTaskOrRestartingNeverReusesAnotherBaseline() throws Exception {
        Path first = Files.createDirectory(temp.resolve("first"));
        Path second = Files.createDirectory(temp.resolve("second"));
        Files.writeString(first.resolve("file"), "same"); Files.writeString(second.resolve("file"), "same");
        BackupService service = new BackupService();
        service.backup(options(first, "first", true, 0, BackupFilter.all()));
        BackupResult b = service.backup(options(second, "second", true, 0, BackupFilter.all()));
        assertFalse(BackupManifest.load(b.manifestPath()).incremental());
        BackupResult c = new BackupService().backup(options(first, "first", true, 0, BackupFilter.all()));
        assertFalse(BackupManifest.load(c.manifestPath()).incremental());
        Path out = temp.resolve("out"); service.restore(c.archivePath(), out, null);
        assertEquals("same", Files.readString(out.resolve("file")));
    }

    @Test void retentionRollsGroupsAndKeepsWholeLatestGroup() throws Exception {
        Path source = Files.createDirectory(temp.resolve("source"));
        Files.writeString(source.resolve("file"), "content");
        BackupService service = new BackupService(); List<BackupResult> results = new ArrayList<>();
        BackupOptions options = options(source, "chain", true, 1, BackupFilter.all());
        for (int i=0; i<12; i++) results.add(service.backup(options));
        for (int i=0; i<10; i++) assertFalse(Files.exists(results.get(i).archivePath()), "old=" + results.get(i).archivePath() + " latest=" + results.get(11).archivePath());
        assertTrue(Files.exists(results.get(10).archivePath()));
        assertTrue(Files.exists(results.get(11).archivePath()));
        BackupManifest full = BackupManifest.load(results.get(10).manifestPath());
        BackupManifest incremental = BackupManifest.load(results.get(11).manifestPath());
        assertFalse(full.incremental()); assertTrue(incremental.incremental());
        assertEquals(full.snapshot().snapshotId(), incremental.snapshot().parentId());
        assertEquals(full.snapshot().groupId(), incremental.snapshot().groupId());
    }

    @Test void failedBackupDoesNotPrunePreviousSuccess() throws Exception {
        Path source = Files.createDirectory(temp.resolve("source")); Files.writeString(source.resolve("file"), "ok");
        BackupService service = new BackupService(); BackupOptions options = options(source, "chain", false, 1, BackupFilter.all());
        BackupResult first = service.backup(options);
        Files.delete(source.resolve("file")); Files.delete(source);
        assertThrows(IOException.class, () -> service.backup(options));
        assertTrue(Files.exists(first.archivePath())); assertTrue(Files.exists(first.manifestPath()));
    }

    @Test void metadataOnlyChangesAreIncludedInIncremental() throws Exception {
        Path source = Files.createDirectory(temp.resolve("source")); Path file = Files.writeString(source.resolve("file"), "same");
        BackupService service = new BackupService(); BackupOptions options = options(source, "chain", true, 0, BackupFilter.all());
        service.backup(options);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        Files.setLastModifiedTime(file, FileTime.fromMillis(1_600_000_000_000L));
        BackupResult second = service.backup(options);
        assertEquals(1, second.archivedEntries());
        Path out = temp.resolve("out"); service.restore(second.archivePath(), out, null);
        assertEquals(1_600_000_000_000L, Files.getLastModifiedTime(out.resolve("file")).toMillis());
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(out.resolve("file"))));
    }

    @Test void sourceAliasCannotPlaceDestinationInsideSource() throws Exception {
        Path source = Files.createDirectory(temp.resolve("source"));
        Path alias = Files.createSymbolicLink(temp.resolve("alias"), source);
        assertThrows(IOException.class, () -> new BackupService().backup(new BackupOptions(alias, source.resolve("nested"), "bad", CompressionType.ZIP, false, false, null)));
    }
}
