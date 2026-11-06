package com.backup.network;

import com.backup.core.BackupCatalog;
import com.backup.core.BackupEntry;
import com.backup.core.BackupManifest;
import com.backup.core.BackupOptions;
import com.backup.core.BackupService;
import com.backup.core.CompressionType;
import com.backup.core.FileScanner;
import com.backup.core.RestoreResult;
import com.backup.crypto.EncryptionService;
import com.backup.io.ArchiveService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

/**
 * Client-side orchestration for cloud backups. Incremental chains follow the same rules as the
 * local service: one full backup plus at most nine increments per task, restarts begin with a
 * full backup, and changing compression, encryption or the passphrase starts a new group.
 * The previous chain state comes from the server's stored manifests instead of a local cache.
 */
/**
 * 客户端网络备份编排：把"本地备份"的语义搬到服务器上，规则与本地完全一致。
 *
 * <p>增量基线来自服务器：以 BackupService.taskIdentity（与本地同一算法）定位任务，
 * 拉取该任务最新快照清单作为 previous；一全量 + 至多九增量、压缩/加密/口令变化或
 * 链缺失退化为全量。加密链的口令一致性由进程内记忆保证（与本地模型一致），
 * 换进程自动重新全量——宁重传不断链。</p>
 *
 * <p>归档在本地临时目录完成（扫描/打包/加密复用本地全套能力），上传后 commit 提交
 * 元数据；还原 = 下载归档 → 调用本地 BackupService.restoreDetailed——
 * 路径安全、链接、元数据语义与本地零差异。</p>
 */
public final class NetworkBackupService {
    private static final DateTimeFormatter ARCHIVE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final int MAX_SEQUENCE = 10;
    private final FileScanner fileScanner = new FileScanner();
    private final ArchiveService archiveService = new ArchiveService();
    private final EncryptionService encryptionService = new EncryptionService();
    private final Map<String, char[]> passphraseMemory = new HashMap<>();

    /** 执行一次网络备份：远端基线 → 本地扫描比对 → 本地写归档（可选加密）→ 上传 + 提交 → 返回结果与警告。 */
    public NetworkBackupResult backup(NetworkBackupClient connection, BackupOptions options, int keepGroups)
            throws IOException, InterruptedException {
        Path source = options.sourceDirectory().toRealPath();
        String taskId = BackupService.taskIdentity(source, options);
        // 只关心本任务的远端快照；sequence 最大者为潜在基线（倒序表已按时间排序，这里再取序号最大以防并列）。
        List<NetworkBackupClient.RemoteSnapshot> remote = connection.listSnapshots().stream()
                .filter(snapshot -> snapshot.taskId().equals(taskId)).toList();
        NetworkBackupClient.RemoteSnapshot latest = remote.stream()
        // 取序号最大的快照作为潜在增量基线（并列时按创建时间）。
                .max(Comparator.comparingInt(NetworkBackupClient.RemoteSnapshot::sequence)
                        .thenComparing(NetworkBackupClient.RemoteSnapshot::createdAt))
                .orElse(null);
        // 口令记忆：加密链要求与上次相同口令才允许续链；记忆为空（新进程）→ 全量。
        String passphraseKey = connection.username() + "/" + taskId;
        char[] remembered = passphraseMemory.get(passphraseKey);
        boolean incremental = options.incremental() && latest != null && latest.sequence() < MAX_SEQUENCE;
        // 序号 <10 才允许续链（与本地组语义一致），否则强制新组全量。
        BackupManifest baseline = null;
        if (incremental) {
            String manifestText = connection.fetchManifest(latest.archiveName());
            if (manifestText == null) {
                incremental = false;
            } else {
                try {
                    baseline = BackupManifest.fromString(manifestText);
                } catch (IOException e) {
                    incremental = false;
                }
            }
        }
        if (incremental && baseline != null) {
        // 增量兼容判定：压缩/加密状态必须与远端基线一致；加密链还要求同口令（进程内记忆）。
            incremental = baseline.compressionType() == options.compressionType()
                    && baseline.encrypted() == options.encrypted()
                    && (!options.encrypted() || Arrays.equals(remembered, options.passphrase()));
        }
        List<String> warnings = new ArrayList<>();
        List<BackupEntry> scanned = fileScanner.scan(source, options.filter(), warnings::add);
        Map<String, BackupEntry> previous = incremental && baseline != null ? baseline.entriesByPath() : Map.of();
        boolean delta = incremental;
        List<BackupEntry> archived = scanned.stream()
                .filter(entry -> entry.directory() || !delta || !entry.sameContentAs(previous.get(entry.relativePath())))
                .toList();
        String snapshotId = UUID.randomUUID().toString();
        String archiveName = archiveFileName(options, incremental, snapshotId);
        BackupCatalog.Snapshot parent = incremental && latest != null
                ? new BackupCatalog.Snapshot(taskId, latest.snapshotId(), latest.groupId(), latest.parentId(),
                        latest.sequence(), latest.archiveName(), latest.createdAt())
                : null;
        BackupCatalog.Snapshot snapshot = new BackupCatalog.Snapshot(taskId, snapshotId,
                incremental ? parent.groupId() : snapshotId,
                incremental ? parent.snapshotId() : "",
                incremental ? parent.sequence() + 1 : 1, archiveName, java.time.Instant.now());
        Path workDirectory = Files.createTempDirectory("backup-network-");
        // 本地暂存目录：归档在此生成（复用本地打包/加密），上传后整体删除。
        Path clear = workDirectory.resolve("archive" + options.compressionType().extension());
        Path payload = options.encrypted() ? workDirectory.resolve("archive.enc") : clear;
        long bytes;
        try {
            BackupManifest manifest = new BackupManifest(source.toString(), snapshot.createdAt(), options.compressionType(),
                    incremental, options.encrypted(), scanned).withSnapshot(snapshot);
            switch (options.compressionType()) {
                case ZIP -> archiveService.writeZipArchive(source, clear, manifest, archived);
                case GZIP -> archiveService.writeGzipArchive(source, clear, manifest, archived);
            }
            if (options.encrypted()) encryptionService.encrypt(clear, payload, options.passphrase());
            bytes = Files.size(payload);
            String token = connection.upload(payload);
            Properties responses = connection.commit(token, manifest.toPropertiesString(), keepGroups);
            for (int i = 0; ; i++) {
                String warning = responses.getProperty("warning." + i);
                if (warning == null) break;
                warnings.add(warning);
            }
        } finally {
            deleteTree(workDirectory);
        }
        if (options.encrypted()) {
            passphraseMemory.put(passphraseKey, options.passphrase().clone());
        } else {
            passphraseMemory.remove(passphraseKey);
        }
        return new NetworkBackupResult(archiveName, !incremental, scanned.size(), archived.size(), bytes,
                List.copyOf(warnings), snapshot);
    }

    /** 网络还原：下载到临时目录后原封调用本地 restoreDetailed——安全语义与本地零差异。 */
    public RestoreResult restore(NetworkBackupClient connection, String archiveName, Path destination, char[] passphrase)
            throws IOException, InterruptedException {
        Path workDirectory = Files.createTempDirectory("backup-restore-network-");
        Path downloaded = workDirectory.resolve(archiveName);
        try {
            connection.download(archiveName, downloaded);
            return new com.backup.core.BackupService().restoreDetailed(downloaded, destination, passphrase);
        } finally {
            deleteTree(workDirectory);
        }
    }

    /** 归档命名与本地同一规则：<名称>-<full|incremental>-<时间戳>-<UUID>.<ext>[.enc]。 */
    private String archiveFileName(BackupOptions options, boolean incremental, String snapshotId) {
        String safe = options.backupName().replaceAll("[^A-Za-z0-9._-]+", "-");
        if (safe.isBlank()) safe = "backup";
        return safe + "-" + (incremental ? "incremental" : "full") + "-" + LocalDateTime.now().format(ARCHIVE_TIMESTAMP)
                + "-" + snapshotId + options.compressionType().extension() + (options.encrypted() ? ".enc" : "");
    }

    /** 递归删除临时工作目录（尽力而为，失败不影响结果）。 */
    private static void deleteTree(Path directory) {
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    /** 网络备份结果：归档名、是否全量、扫描/归档条目数、字节数、警告与发布的快照身份。 */
    public record NetworkBackupResult(String archiveName, boolean full, int scannedEntries, int archivedEntries,
                                      long bytes, List<String> warnings, BackupCatalog.Snapshot snapshot) {}
}
