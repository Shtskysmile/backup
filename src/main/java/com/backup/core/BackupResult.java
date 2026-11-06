package com.backup.core;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * 一次备份的结果快照：归档与清单路径、扫描/归档条目数（差值即"增量省下的"）、
 * 字节数、耗时、加密标志与淘汰警告。warnings 拷贝为不可变列表，防止外部污染。
 */
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
    /** 无警告便捷构造（警告默认空列表）。 */
    public BackupResult(Path archive, Path manifest, int scanned, int archived, long bytes, Duration duration, boolean encrypted) {
        this(archive, manifest, scanned, archived, bytes, duration, encrypted, List.of());
    }
}
