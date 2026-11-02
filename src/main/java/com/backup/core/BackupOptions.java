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
        char[] passphrase,
        BackupFilter filter,
        int keepGroups
) {
    public BackupOptions(Path source, Path destination, String name, CompressionType compression,
                         boolean incremental, boolean encrypted, char[] password) {
        this(source, destination, name, compression, incremental, encrypted, password, BackupFilter.all(), 0);
    }

    public BackupOptions {
        filter = filter == null ? BackupFilter.all() : filter;
        if (keepGroups < 0) throw new IllegalArgumentException("Retained group count must not be negative");
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
