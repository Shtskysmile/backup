package com.backup.core;

import java.nio.file.Path;
import java.time.Duration;

public record BackupResult(
        Path archivePath,
        Path manifestPath,
        int scannedEntries,
        int archivedEntries,
        long archivedBytes,
        Duration duration,
        boolean encrypted
) {
}
