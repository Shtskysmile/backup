package com.backup.io;

import com.backup.core.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SpecialFileTypeTest {
    @TempDir Path temp;

    private void assumeUnix() throws Exception {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("unix")
                && Files.getFileStore(temp).supportsFileAttributeView("posix"), "FIFO requires a POSIX filesystem; unsupported Windows capabilities are skipped");
        Path probe = temp.resolve("capability-pipe");
        try {
            Process command = new ProcessBuilder("mkfifo", probe.toString()).start();
            boolean exited = command.waitFor(5, TimeUnit.SECONDS);
            if (!exited) command.destroyForcibly();
            assumeTrue(exited && command.exitValue() == 0, "mkfifo not available for this filesystem");
        } catch (IOException e) { assumeTrue(false, "mkfifo unavailable: " + e.getMessage()); }
        finally { Files.deleteIfExists(probe); }
    }

    @Test void zipFifoRoundTripAndPermissionIncrementFinishWithoutBlocking() throws Exception { roundTrip("ZIP", false); }
    @Test void gzipFifoRoundTripAndPermissionIncrementFinishWithoutBlocking() throws Exception { roundTrip("GZIP", false); }
    @Test void encryptedFifoRoundTripFinishesWithoutBlocking() throws Exception { roundTrip("GZIP", true); }

    private void roundTrip(String format, boolean encrypted) throws Exception {
        assumeUnix();
        Path log = temp.resolve("child.log");
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                FifoRoundTripProbe.class.getName(), temp.resolve("roundtrip").toString(), format, Boolean.toString(encrypted))
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(child.waitFor(12, TimeUnit.SECONDS), "FIFO restore hung in native attribute code");
            assertEquals(0, child.exitValue(), Files.readString(log));
        } finally {
            if (child.isAlive()) { child.destroyForcibly(); child.waitFor(5, TimeUnit.SECONDS); }
        }
    }

    private BackupManifest manifest() {
        BackupEntry entry = new BackupEntry("pipe", false, 0, 1_600_000_000_000L, "", "rw-r-----", "", "",
                EntryType.FIFO, "", 1_600_000_000_000L, null);
        return new BackupManifest("source", Instant.now(), CompressionType.ZIP, false, false, List.of(entry));
    }
    private Path archive() throws Exception {
        Path source = Files.createDirectory(temp.resolve("source"));
        Path archive = temp.resolve("pipe.zip");
        new ArchiveService().writeZipArchive(source, archive, manifest(), manifest().entries());
        return archive;
    }

    @Test void unavailableMkfifoPreservesExistingFileAndReportsWarning() throws Exception {
        Path archive = archive(); Path out = Files.createDirectory(temp.resolve("out"));
        Path existing = Files.writeString(out.resolve("pipe"), "keep");
        long modified = Files.getLastModifiedTime(existing).toMillis();
        ArchiveService service = new ArchiveService(new FifoSupport(temp.resolve("missing-mkfifo").toString(), "touch"));
        RestoreResult result = service.restoreDetailed(archive, out);
        assertEquals("keep", Files.readString(existing));
        assertEquals(modified, Files.getLastModifiedTime(existing).toMillis());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("pipe") && w.contains("not recreated")));
    }

    @Test void nonzeroMkfifoExitIsNotSilentlyAcceptedForExistingTarget() throws Exception {
        assumeUnix();
        Path fake = Files.writeString(temp.resolve("failed-mkfifo"), "#!/bin/sh\necho 'simulated mkfifo failure' >&2\nexit 17\n");
        Files.setPosixFilePermissions(fake, PosixFilePermissions.fromString("rwx------"));
        Path archive = archive(); Path out = Files.createDirectory(temp.resolve("out"));
        Files.writeString(out.resolve("pipe"), "keep");
        RestoreResult result = new ArchiveService(new FifoSupport(fake.toString(), "touch")).restoreDetailed(archive, out);
        assertEquals("keep", Files.readString(out.resolve("pipe")));
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("17") && w.contains("not recreated")), result.warnings().toString());
    }

    @Test void fifoMatchesZeroByteFilterWithoutReadingItsStream() throws Exception {
        assumeUnix();
        Path source = Files.createDirectory(temp.resolve("source"));
        Process maker = new ProcessBuilder("mkfifo", source.resolve("pipe").toString()).start();
        assertTrue(maker.waitFor(5, TimeUnit.SECONDS)); assertEquals(0, maker.exitValue());
        Files.writeString(source.resolve("regular"), "data");
        BackupFilter filter = new BackupFilter("", "", "", false, "", null, null, 0L, 0L);
        List<BackupEntry> entries = new FileScanner().scan(source, filter, ignored -> {});
        assertEquals(List.of("pipe"), entries.stream().map(BackupEntry::relativePath).toList());
        assertEquals(EntryType.FIFO, entries.getFirst().type());
        assertEquals(0, entries.getFirst().size());
    }

    @Test void malformedFifoPayloadIsRejectedBeforeChangingDestination() throws Exception {
        BackupEntry entry = new BackupEntry("pipe", false, 1, 0, "", "", "", "", EntryType.FIFO, "", null, null);
        BackupManifest manifest = new BackupManifest("source", Instant.now(), CompressionType.ZIP, false, false, List.of(entry));
        Path archive = temp.resolve("malformed.zip");
        new ArchiveService().writeZipArchive(temp, archive, manifest, manifest.entries());
        Path out = Files.createDirectory(temp.resolve("out")); Files.writeString(out.resolve("pipe"), "keep");
        assertThrows(IOException.class, () -> new ArchiveService().restoreDetailed(archive, out));
        assertEquals("keep", Files.readString(out.resolve("pipe")));
    }

    @Test void wrongIncrementalFifoBaselineIsWarnedWithoutChangingRegularFile() throws Exception {
        BackupManifest delta = new BackupManifest("source", Instant.now(), CompressionType.ZIP, true, false, manifest().entries());
        Path archive = temp.resolve("delta.zip");
        new ArchiveService().writeZipArchive(temp, archive, delta, List.of());
        Path out = Files.createDirectory(temp.resolve("out")); Path old = Files.writeString(out.resolve("pipe"), "keep");
        long modified = Files.getLastModifiedTime(old).toMillis();
        RestoreResult result = new ArchiveService().restoreDetailed(archive, out);
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("baseline")));
        assertEquals("keep", Files.readString(old));
        assertEquals(modified, Files.getLastModifiedTime(old).toMillis());
    }

    @Test void zeroExitWithoutCreatingPipeIsReportedAndOriginalTargetPreserved() throws Exception {
        assumeUnix();
        Path fake = Files.writeString(temp.resolve("fake-mkfifo"), "#!/bin/sh\nprintf wrong > \"$3\"\nexit 0\n");
        Files.setPosixFilePermissions(fake, PosixFilePermissions.fromString("rwx------"));
        Path archive = archive(); Path out = Files.createDirectory(temp.resolve("out")); Files.writeString(out.resolve("pipe"), "keep");
        RestoreResult result = new ArchiveService(new FifoSupport(fake.toString(), "touch")).restoreDetailed(archive, out);
        assertEquals("keep", Files.readString(out.resolve("pipe")));
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("did not create a FIFO")));
    }

    @Test void missingTimeToolReportsAttributeWarningsWithoutBlocking() throws Exception {
        assumeUnix();
        Path archive = archive();
        RestoreResult result = new ArchiveService(new FifoSupport("mkfifo", temp.resolve("missing-touch").toString()))
                .restoreDetailed(archive, temp.resolve("out"));
        assertTrue(FifoSupport.isFifo(temp.resolve("out/pipe")));
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("could not restore FIFO modified time")));
    }

    @Test void replacingNonemptyDirectoryFailsWithoutRemovingItsContents() throws Exception {
        assumeUnix();
        Path archive = archive(); Path out = Files.createDirectories(temp.resolve("out/pipe"));
        Files.writeString(out.resolve("existing.txt"), "keep");
        RestoreResult result = new ArchiveService().restoreDetailed(archive, out.getParent());
        assertEquals("keep", Files.readString(out.resolve("existing.txt")));
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("not recreated")));
    }

    @Test void unchangedIncrementRestoresFifoPermissionsAfterDestinationDrift() throws Exception {
        assumeUnix();
        Path source = Files.createDirectory(temp.resolve("source"));
        Process maker = new ProcessBuilder("mkfifo", "-m", "600", source.resolve("pipe").toString()).start();
        assertTrue(maker.waitFor(5, TimeUnit.SECONDS)); assertEquals(0, maker.exitValue());
        BackupService service = new BackupService();
        BackupOptions options = new BackupOptions(source, temp.resolve("backups"), "pipe", CompressionType.ZIP, true, false, null);
        BackupResult full = service.backup(options);
        Path out = temp.resolve("out"); service.restoreDetailed(full.archivePath(), out, null);
        Files.setPosixFilePermissions(out.resolve("pipe"), PosixFilePermissions.fromString("rw-rw-rw-"));
        BackupResult increment = service.backup(options);
        assertEquals(0, increment.archivedEntries());
        service.restoreDetailed(increment.archivePath(), out, null);
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(out.resolve("pipe"), LinkOption.NOFOLLOW_LINKS)));
    }

    @Test void deviceEntriesRestoreWithWarningWhenRecreationIsImpossible() throws Exception {
        Path source = Files.createDirectory(temp.resolve("source"));
        Path file = Files.writeString(source.resolve("file"), "content");
        List<BackupEntry> scanned = new FileScanner().scan(source);
        BackupEntry device = new BackupEntry("ttyp3", false, 0, 1_600_000_000_000L, "", "rw-rw-rw-",
                "", "", EntryType.CHARACTER_DEVICE, "4:3", null, null);
        BackupManifest manifest = new BackupManifest(source.toString(), Instant.now(), CompressionType.ZIP, false, false,
                List.of(scanned.getFirst(), device));
        Path archive = temp.resolve("device.zip");
        new ArchiveService().writeZipArchive(source, archive, manifest, List.of(scanned.getFirst(), device));
        Path out = temp.resolve("device-out");
        RestoreResult result = new ArchiveService().restoreDetailed(archive, out);
        assertEquals("content", Files.readString(out.resolve("file")), "Regular entries restore normally");
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("ttyp3") && w.contains("CHARACTER_DEVICE")),
                "Expected a recreation warning: " + result.warnings());
    }

    @Test void invalidDeviceNumbersAreRejectedBeforeAnyWrite() throws Exception {
        Path source = Files.createDirectory(temp.resolve("source"));
        Files.writeString(source.resolve("file"), "content");
        for (String bad : List.of("", "abc", "1:99999999", "-1:3", "1:2:3")) {
            BackupEntry device = new BackupEntry("dev", false, 0, 1_600_000_000_000L, "", "", "",
                    "", EntryType.BLOCK_DEVICE, bad, null, null);
            BackupManifest manifest = new BackupManifest(source.toString(), Instant.now(), CompressionType.ZIP, false, false, List.of(device));
            Path archive = temp.resolve("bad-" + Math.abs(bad.hashCode()) + ".zip");
            new ArchiveService().writeZipArchive(source, archive, manifest, List.of(device));
            Path out = temp.resolve("out-" + Math.abs(bad.hashCode()));
            assertThrows(IOException.class, () -> new ArchiveService().restoreDetailed(archive, out), "device=" + bad);
            assertFalse(Files.exists(out.resolve("dev")));
        }
    }

    @Test void socketEntryIsRecordedButNeverRecreated() throws Exception {
        Path source = Files.createDirectory(temp.resolve("source"));
        Path file = Files.writeString(source.resolve("file"), "content");
        List<BackupEntry> scanned = new FileScanner().scan(source);
        BackupEntry socket = new BackupEntry("daemon.sock", false, 0, 1_600_000_000_000L, "", "", "",
                "", EntryType.SOCKET, "", null, null);
        BackupManifest manifest = new BackupManifest(source.toString(), Instant.now(), CompressionType.ZIP, false, false,
                List.of(scanned.getFirst(), socket));
        Path archive = temp.resolve("socket.zip");
        new ArchiveService().writeZipArchive(source, archive, manifest, List.of(scanned.getFirst(), socket));
        Path out = temp.resolve("socket-out");
        RestoreResult result = new ArchiveService().restoreDetailed(archive, out);
        assertEquals("content", Files.readString(out.resolve("file")));
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("daemon.sock") && w.contains("socket")));
        assertFalse(Files.exists(out.resolve("daemon.sock")));
    }
}
