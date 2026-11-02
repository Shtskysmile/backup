package com.backup.core;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;

public record BackupOptions(
        Path sourceDirectory,
        Path destinationDirectory,
        String backupName,
        CompressionType compressionType,
        boolean incremental,
        boolean encrypted,
        char[] passphrase
) {
    public BackupOptions {
        Objects.requireNonNull(sourceDirectory, "sourceDirectory");
        Objects.requireNonNull(destinationDirectory, "destinationDirectory");
        compressionType = compressionType == null ? CompressionType.ZIP : compressionType;
        backupName = backupName == null || backupName.isBlank()
                ? sourceDirectory.getFileName() == null ? "backup" : sourceDirectory.getFileName().toString()
                : backupName.trim();
        passphrase = passphrase == null ? new char[0] : Arrays.copyOf(passphrase, passphrase.length);
        if (encrypted && passphrase.length == 0) {
            throw new IllegalArgumentException("Encrypted backups require a passphrase.");
        }
    }

    @Override
    public char[] passphrase() {
        return Arrays.copyOf(passphrase, passphrase.length);
    }
}
