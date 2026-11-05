package com.backup.io;

import com.backup.core.BackupEntry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** POSIX pipe operations never open a pipe's data stream, including while restoring attributes. */
final class FifoSupport {
    private static final DateTimeFormatter TOUCH_TIME = DateTimeFormatter.ofPattern("uuuuMMddHHmm.ss").withZone(ZoneOffset.UTC);
    private final String mkfifo;
    private final String touch;

    FifoSupport() { this("mkfifo", "touch"); }
    FifoSupport(String mkfifo, String touch) { this.mkfifo = mkfifo; this.touch = touch; }

    static boolean isFifo(Path path) {
        try {
            Object mode = Files.getAttribute(path, "unix:mode", LinkOption.NOFOLLOW_LINKS);
            return mode instanceof Integer bits && (bits & 0170000) == 0010000;
        } catch (IOException | UnsupportedOperationException | IllegalArgumentException e) { return false; }
    }

    void create(BackupEntry entry, Path staged) throws IOException {
        int mode = 0600;
        if (!entry.permissions().isBlank()) {
            mode = 0;
            for (PosixFilePermission permission : PosixFilePermissions.fromString(entry.permissions())) {
                mode |= switch (permission) {
                    case OWNER_READ -> 0400; case OWNER_WRITE -> 0200; case OWNER_EXECUTE -> 0100;
                    case GROUP_READ -> 0040; case GROUP_WRITE -> 0020; case GROUP_EXECUTE -> 0010;
                    case OTHERS_READ -> 0004; case OTHERS_WRITE -> 0002; case OTHERS_EXECUTE -> 0001;
                };
            }
        }
        run(List.of(mkfifo, "-m", Integer.toOctalString(mode), staged.toString()));
        if (!isFifo(staged)) throw new IOException("mkfifo did not create a FIFO");
    }

    void restoreTimes(BackupEntry entry, Path path, Consumer<String> warnings) {
        restoreTime(entry, path, "-m", entry.lastModifiedMillis(), "modified time", warnings);
        if (entry.lastAccessMillis() != null) restoreTime(entry, path, "-a", entry.lastAccessMillis(), "access time", warnings);
        if (entry.creationMillis() != null) warnings.accept(entry.relativePath() + ": FIFO creation time is not restored on this platform");
    }

    private void restoreTime(BackupEntry entry, Path path, String flag, long millis, String name, Consumer<String> warnings) {
        try {
            // -h prevents following a replaced symlink; UTC avoids local DST ambiguity.
            run(List.of(touch, "-c", "-h", flag, "-t", TOUCH_TIME.format(Instant.ofEpochMilli(millis)), path.toString()));
            if (Math.floorMod(millis, 1000) != 0) warnings.accept(entry.relativePath() + ": FIFO " + name + " restored with whole-second precision");
        } catch (IOException | RuntimeException e) {
            warnings.accept(entry.relativePath() + ": could not restore FIFO " + name + " (" + e.getMessage() + ")");
        }
    }

    private void run(List<String> command) throws IOException {
        Path log = Files.createTempFile("backup-fifo-command-", ".log");
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
            builder.environment().put("TZ", "UTC0");
            process = builder.start();
            if (!process.waitFor(5, TimeUnit.SECONDS)) throw new IOException(command.getFirst() + " timed out after 5 seconds");
            if (process.exitValue() != 0) {
                byte[] bytes;
                try (var in = Files.newInputStream(log)) { bytes = in.readNBytes(4096); }
                throw new IOException(command.getFirst() + " exited " + process.exitValue() + ": " + new String(bytes, StandardCharsets.UTF_8).strip());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(command.getFirst() + " interrupted", e);
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            Files.deleteIfExists(log);
        }
    }
}
