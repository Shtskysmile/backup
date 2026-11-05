package com.backup.io;

import com.backup.core.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

/** Runs in a child JVM so a native FIFO open regression cannot hang the test runner. */
public class FifoRoundTripProbe {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        CompressionType type = CompressionType.valueOf(args[1]);
        boolean encrypted = Boolean.parseBoolean(args[2]);
        Path source = Files.createDirectories(root.resolve("source"));
        Path pipe = source.resolve("pipe");
        Process maker = new ProcessBuilder("mkfifo", "-m", "640", pipe.toString()).start();
        if (!maker.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) { maker.destroyForcibly(); throw new AssertionError("mkfifo timed out"); }
        if (maker.exitValue() != 0) throw new AssertionError("mkfifo failed");
        List<BackupEntry> scanned = new FileScanner().scan(source);
        if (scanned.size() != 1 || scanned.getFirst().type() != EntryType.FIFO || scanned.getFirst().size() != 0) throw new AssertionError("FIFO was not captured as zero-payload entry");
        BackupService service = new BackupService();
        char[] password = "fifo-roundtrip".toCharArray();
        BackupOptions options = new BackupOptions(source, root.resolve("backups"), "pipes", type, true, encrypted, password);
        BackupResult first = service.backup(options);
        Path out = root.resolve("restored");
        RestoreResult result = service.restoreDetailed(first.archivePath(), out, password);
        BackupEntry expected = BackupManifest.load(first.manifestPath()).entries().getFirst();
        checkPipe(out.resolve("pipe"), "rw-r-----");
        BasicFileAttributes times = Files.readAttributes(out.resolve("pipe"), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (times.lastModifiedTime().toMillis() != Math.floorDiv(expected.lastModifiedMillis(), 1000) * 1000
                || times.lastAccessTime().toMillis() != Math.floorDiv(expected.lastAccessMillis(), 1000) * 1000) {
            throw new AssertionError("FIFO modified/access times were not restored to whole seconds");
        }
        if (result.warnings().stream().anyMatch(w -> w.contains("not recreated"))) throw new AssertionError(result.warnings());
        Files.setPosixFilePermissions(pipe, PosixFilePermissions.fromString("rw-------"));
        BackupResult second = service.backup(options);
        if (second.archivedEntries() != 1 || !BackupManifest.load(second.manifestPath()).incremental()) throw new AssertionError("FIFO metadata change omitted from increment");
        service.restoreDetailed(second.archivePath(), out, password);
        checkPipe(out.resolve("pipe"), "rw-------");
        System.out.println("PASS " + type + " encrypted=" + encrypted + " full+increment, warnings=" + result.warnings());
    }
    private static void checkPipe(Path pipe, String permissions) throws Exception {
        int mode = (Integer) Files.getAttribute(pipe, "unix:mode", LinkOption.NOFOLLOW_LINKS);
        if ((mode & 0170000) != 0010000) throw new AssertionError("Restored object is not a FIFO");
        if (!PosixFilePermissions.toString(Files.getPosixFilePermissions(pipe, LinkOption.NOFOLLOW_LINKS)).equals(permissions)) throw new AssertionError("FIFO permissions differ");
    }
}
