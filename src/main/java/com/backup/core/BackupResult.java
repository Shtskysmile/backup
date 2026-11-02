package com.backup.core;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

public record BackupResult(
        Path archivePath,
        Path manifestPath,
        int scannedEntries,
        int archivedEntries,
        long archivedBytes,
        Duration duration,
        boolean encrypted,
        List<String> warnings
) {
    public BackupResult { warnings = List.copyOf(warnings); }
    public BackupResult(Path archive, Path manifest, int scanned, int archived, long bytes, Duration duration, boolean encrypted) {
        this(archive, manifest, scanned, archived, bytes, duration, encrypted, List.of());
    }
}
