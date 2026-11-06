package com.backup.core;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;

/**
 * 一次备份的全部参数（record 不可变值对象，模块间唯一参数契约）。
 *
 * <p>compact constructor 负责所有规范化：null 筛选退化为"全部文件"、压缩缺省 ZIP、
 * 备份名缺省取源目录名、口令做防御性拷贝（外部修改不影响了；{@link #passphrase()}
 * 每次返回副本，内部状态永不泄漏）。加密开启但口令为空在构造期即抛出——
 * 坏参数活不过 new，不会流到扫描或打包阶段。</p>
 */
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
        // 参数兜底顺序：筛选 → keepGroups 非负 → 源/目标非空 → 压缩缺省 → 名称缺省 → 口令拷贝。
        if (keepGroups < 0) throw new IllegalArgumentException("Retained group count must not be negative");
        Objects.requireNonNull(sourceDirectory, "sourceDirectory");
        Objects.requireNonNull(destinationDirectory, "destinationDirectory");
        compressionType = compressionType == null ? CompressionType.ZIP : compressionType;
        backupName = backupName == null || backupName.isBlank()
                ? sourceDirectory.getFileName() == null ? "backup" : sourceDirectory.getFileName().toString()
                : backupName.trim();
        passphrase = passphrase == null ? new char[0] : Arrays.copyOf(passphrase, passphrase.length);
        if (encrypted && passphrase.length == 0) {
        // 加密与空口令互斥：在构造期就拒绝，UI 层也因此能放心提交。
            throw new IllegalArgumentException("Encrypted backups require a passphrase.");
        }
    }

    @Override
    /** 口令副本：外部修改不影响内部状态（防御性拷贝双向隔离）。 */
    public char[] passphrase() {
        return Arrays.copyOf(passphrase, passphrase.length);
    }
}
