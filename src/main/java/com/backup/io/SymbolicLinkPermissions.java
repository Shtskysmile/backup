package com.backup.io;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Restores a link's own mode without invoking JDK setters that can follow its target on macOS. */
final class SymbolicLinkPermissions {
    private final String chmod;
    private final boolean mutableLinkModes;

    SymbolicLinkPermissions() {
        this("/bin/chmod", System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac"));
    }
    SymbolicLinkPermissions(String chmod, boolean mutableLinkModes) {
        this.chmod = chmod; this.mutableLinkModes = mutableLinkModes;
    }

    void restore(Path link, Set<PosixFilePermission> permissions) throws IOException {
        requireLink(link);
        if (Files.getPosixFilePermissions(link, LinkOption.NOFOLLOW_LINKS).equals(permissions)) return;
        if (!mutableLinkModes) throw new UnsupportedOperationException("Changing symbolic-link permissions is not supported on this platform");
        int mode = 0;
        for (PosixFilePermission permission : permissions) mode |= switch (permission) {
            case OWNER_READ -> 0400; case OWNER_WRITE -> 0200; case OWNER_EXECUTE -> 0100;
            case GROUP_READ -> 0040; case GROUP_WRITE -> 0020; case GROUP_EXECUTE -> 0010;
            case OTHERS_READ -> 0004; case OTHERS_WRITE -> 0002; case OTHERS_EXECUTE -> 0001;
        };
        Path log = Files.createTempFile("backup-link-permissions-", ".log");
        Process process = null;
        try {
            // BSD chmod -h uses lchmod: broken/absolute/directory links are never dereferenced.
            process = new ProcessBuilder(chmod, "-h", Integer.toOctalString(mode), link.toAbsolutePath().toString())
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            if (!process.waitFor(5, TimeUnit.SECONDS)) throw new IOException("Symbolic-link chmod timed out after 5 seconds");
            if (process.exitValue() != 0) {
                byte[] bytes;
                try (var in = Files.newInputStream(log)) { bytes = in.readNBytes(4096); }
                throw new IOException("Symbolic-link chmod exited " + process.exitValue() + ": " + new String(bytes, StandardCharsets.UTF_8).strip());
            }
            requireLink(link);
            if (!Files.getPosixFilePermissions(link, LinkOption.NOFOLLOW_LINKS).equals(permissions)) {
                throw new IOException("Symbolic-link permissions differ from the manifest after chmod");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new IOException("Symbolic-link chmod interrupted", e);
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            Files.deleteIfExists(log);
        }
    }

    private static void requireLink(Path link) throws IOException {
        if (!Files.isSymbolicLink(link)) throw new IOException("Expected a symbolic link for permission restoration: " + link);
    }
}
