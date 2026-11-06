package com.backup.core;

import com.backup.crypto.EncryptionService;
import com.backup.io.ArchiveService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 编排一次备份或还原的完整生命周期，是本地模式的核心入口。
 *
 * <p>备份流水线：参数校验（目标不得位于源内）→ 计算任务身份 → 扫描 → 增量判定 →
 * 写明文归档 → （可选）加密 → 原子发布归档与两个旁挂文件 → 更新进程内基线 →
 * 执行组保留淘汰。所有发布步骤遵循"临时文件 + ATOMIC_MOVE + 失败回滚"的事务纪律，
 * 保证目标目录在任何时刻都处于合法状态。</p>
 *
 * <p>增量安全的第一原则是"宁全量不猜链"：基线仅存于当前服务实例（key =
 * 目标目录 + 任务身份），任一兼容条件（压缩/加密/口令/保留开关/链文件齐全/序号未满 10）
 * 不满足即自动退化为全量，绝不跨进程猜测旧链。</p>
 *
 * <p>实例方法均为 synchronized：同一实例内的备份与还原天然串行，
 * 与 UI 层 BackupTaskGate 的任务闸门形成两级串行保护。</p>
 */
/** Single-instance operations are serialized; no server or persistent password state is required. */
public final class BackupService {
    private static final String LATEST_MANIFEST_NAME = ".backpack-manifest.properties";
    private static final DateTimeFormatter ARCHIVE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private final FileScanner fileScanner;
    private final ArchiveService archiveService;
    private final EncryptionService encryptionService;
    private final Map<String, Baseline> baselines = new HashMap<>();

    /** 产品默认构造：真实扫描器/归档/加密服务。 */
    public BackupService() { this(new FileScanner(), new ArchiveService(), new EncryptionService()); }
    /** 注入构造：测试可替换依赖（构造器注入，见需求书 4.3 可维护性）。 */
    public BackupService(FileScanner scanner, ArchiveService archive, EncryptionService encryption) {
        fileScanner = scanner; archiveService = archive; encryptionService = encryption;
    }

    /** 执行一次备份：返回归档路径、清单路径、扫描/归档条目数、字节数与淘汰警告。失败则不发布任何产物。 */
    public synchronized BackupResult backup(BackupOptions options) throws IOException {
        Instant started = Instant.now();
        Path source = options.sourceDirectory().toRealPath();
        Path destination = options.destinationDirectory().toAbsolutePath().normalize();
        // 双重校验：先按用户输入的字面路径，再按 toRealPath 解析符号链接后的真实路径，
        // 防止通过源目录内的符号链接把目标"偷渡"到源外（见 sourceAliasCannotPlaceDestinationInsideSource 测试）。
        if (destination.equals(source) || destination.startsWith(source)) throw new IOException("Destination directory must not be inside the source directory.");
        Files.createDirectories(destination); destination = destination.toRealPath();
        if (destination.equals(source) || destination.startsWith(source)) throw new IOException("Destination directory must not be inside the source directory.");
        String taskId = taskIdentity(source, options);
        // 基线按"目标目录 + 任务身份"隔离：同目录的不同任务互不续链。
        String key = destination + "/" + taskId;
        Baseline baseline = baselines.get(key);
        boolean incremental = options.incremental() && baseline != null && baseline.compatible(options)
                && baseline.manifest().snapshot().sequence() < 10 && baseline.chainAvailable();
        List<String> warnings = new ArrayList<>();
        List<BackupEntry> scanned = fileScanner.scan(source, options.filter(), warnings::add);
        // 增量语义：清单始终记录完整快照，归档载荷只放与基线不同的条目；目录条目恒随附（还原需要 mkdir）。
        Map<String, BackupEntry> previous = incremental ? baseline.manifest().entriesByPath() : Map.of();
        List<BackupEntry> archived = scanned.stream().filter(entry -> !incremental || entry.directory() || !entry.sameContentAs(previous.get(entry.relativePath()))).toList();
        String snapshotId = UUID.randomUUID().toString();
        Path archive = uniqueArchivePath(destination, archiveFileName(options, incremental, snapshotId));
        Path archiveManifest = destination.resolve(archive.getFileName() + ".manifest.properties");
        Path index = destination.resolve(archive.getFileName() + ".snapshot.properties");
        Instant created = Instant.now();
        // 组语义：全量 sequence=1 且 groupId=snapshotId；增量继承组 ID 并把父指向上一快照。
        BackupCatalog.Snapshot parent = incremental ? baseline.manifest().snapshot() : null;
        BackupCatalog.Snapshot snapshot = new BackupCatalog.Snapshot(taskId, snapshotId,
                incremental ? parent.groupId() : snapshotId, incremental ? parent.snapshotId() : "",
                incremental ? parent.sequence() + 1 : 1, archive.getFileName().toString(), created);
        BackupManifest manifest = new BackupManifest(source.toString(), created, options.compressionType(), incremental,
                options.encrypted(), scanned).withSnapshot(snapshot);
        // 事务式发布：staged 为最终产物临时名；clear 为加密前的明文临时名（仅加密时与 staged 分离）。
        Path staged = Files.createTempFile(destination, ".backup-writing-", ".part");
        Path clear = options.encrypted() ? Files.createTempFile(destination, ".backup-clear-", ".part") : staged;
        boolean published = false, archiveMoved = false;
        long bytes;
        try {
            switch (options.compressionType()) {
                case ZIP -> archiveService.writeZipArchive(source, clear, manifest, archived);
                case GZIP -> archiveService.writeGzipArchive(source, clear, manifest, archived);
            }
            if (options.encrypted()) encryptionService.encrypt(clear, staged, options.passphrase());
            bytes = Files.size(staged);
            Files.move(staged, archive, StandardCopyOption.ATOMIC_MOVE); archiveMoved = true;
            manifest.store(archiveManifest);
            BackupCatalog.publish(destination, snapshot);
            // 兼容性最新清单：仅供人工检视，不作为跨进程续接依据（见需求书 v2 修订）。
            manifest.store(destination.resolve(LATEST_MANIFEST_NAME));
            published = true;
        } finally {
            // 回滚纪律：任何一步失败都删除本次已产生的临时/半发布产物，保留历史归档。
            Files.deleteIfExists(staged);
            if (!clear.equals(staged)) Files.deleteIfExists(clear);
            if (!published && archiveMoved) {
                Files.deleteIfExists(index); Files.deleteIfExists(archiveManifest); Files.deleteIfExists(archive);
            }
        }
        List<Path> chain = new ArrayList<>(); if (incremental) chain.addAll(baseline.chain()); chain.add(archive);
        // 口令以副本形式保存在基线中（char[]），替换旧基线时立即清零旧口令，缩短明文驻留窗口。
        Baseline replacement = new Baseline(manifest, options.compressionType(), options.encrypted(),
                options.encrypted() ? options.passphrase() : new char[0], options.keepGroups() > 0, List.copyOf(chain));
        Baseline old = baselines.put(key, replacement); if (old != null) Arrays.fill(old.passphrase(), '\0');
        // 淘汰失败不影响备份主体的成功性，只作为警告返回。
        try { BackupCatalog.prune(destination, taskId, options.keepGroups(), warnings::add); }
        catch (IOException | RuntimeException e) { warnings.add("Backup completed, but retention failed: " + e.getMessage()); }
        return new BackupResult(archive, archiveManifest, scanned.size(), archived.size(), bytes,
                Duration.between(started, Instant.now()), options.encrypted(), warnings);
    }

    /** 还原单个归档（兼容旧调用方）；详细结果与警告见 {@link #restoreDetailed}。 */
    public synchronized BackupManifest restore(Path archive, Path destination, char[] passphrase) throws IOException {
        return restoreDetailed(archive, destination, passphrase).manifest();
    }
    /** 还原单个归档并返回完整结果；加密归档先解密到临时文件，还原结束立即删除。 */
    public synchronized RestoreResult restoreDetailed(Path archive, Path destination, char[] passphrase) throws IOException {
        Path input = archive.toAbsolutePath().normalize(); Path temporary = null;
        try {
            if (encryptionService.isEncrypted(input)) {
                if (passphrase == null || passphrase.length == 0) throw new IOException("Encrypted archive requires a passphrase.");
                temporary = encryptionService.decryptToTempFile(input, passphrase); input = temporary;
            }
            return archiveService.restoreDetailed(input, destination);
        } finally { if (temporary != null) Files.deleteIfExists(temporary); }
    }

    /** 稳定任务身份：本地与网络备份共用。长度前缀拼接防止路径/名称拼接歧义（"ab"+"c" vs "a"+"bc"）。 */
    public static String taskIdentity(Path source, BackupOptions options) {
        try {
            String name = options.backupName(), filter = options.filter().identity(), path = source.toString();
            String identity = path.length() + ":" + path + name.length() + ":" + name + filter.length() + ":" + filter;
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    /** 归档命名规则：<安全化的名称>-<full|incremental>-<时间戳>-<UUID>.<ext>[.enc]，UUID 保证同秒不冲突。 */
    private String archiveFileName(BackupOptions options, boolean incremental, String snapshotId) {
        String safe = options.backupName().replaceAll("[^A-Za-z0-9._-]+", "-");
        if (safe.isBlank()) safe = "backup";
        return safe + "-" + (incremental ? "incremental" : "full") + "-" + LocalDateTime.now().format(ARCHIVE_TIMESTAMP) + "-" + snapshotId
                + options.compressionType().extension() + (options.encrypted() ? ".enc" : "");
    }
    /** 归档名冲突防护：归档本体与两个旁挂文件任一存在则追加 -N 后缀重试。 */
    private Path uniqueArchivePath(Path destination, String filename) {
        Path candidate = destination.resolve(filename); int suffix = 1;
        while (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)
                || Files.exists(candidate.resolveSibling(candidate.getFileName() + ".manifest.properties"), LinkOption.NOFOLLOW_LINKS)
                || Files.exists(candidate.resolveSibling(candidate.getFileName() + ".snapshot.properties"), LinkOption.NOFOLLOW_LINKS)) {
            candidate = destination.resolve(filename.replaceFirst("(\\.[^.]+(?:\\.enc)?)$", "-" + suffix++ + "$1"));
        }
        return candidate;
    }
    /**
     * 进程内基线：记录上次发布的清单、格式选项、口令与链文件列表。
     * compatible 决定增量是否可行（任一格式条件变化即断链开新组）；
     * chainAvailable 检查链上归档与旁挂文件仍齐全，缺失则放弃续接。
     */
    private record Baseline(BackupManifest manifest, CompressionType compression, boolean encrypted,
                            char[] passphrase, boolean retentionEnabled, List<Path> chain) {
        boolean compatible(BackupOptions options) {
            return compression == options.compressionType() && encrypted == options.encrypted()
                    && (!encrypted || Arrays.equals(passphrase, options.passphrase()))
                    && retentionEnabled == (options.keepGroups() > 0);
        }
        boolean chainAvailable() {
            return chain.stream().allMatch(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    && Files.isRegularFile(path.resolveSibling(path.getFileName() + ".manifest.properties"), LinkOption.NOFOLLOW_LINKS)
                    && Files.isRegularFile(path.resolveSibling(path.getFileName() + ".snapshot.properties"), LinkOption.NOFOLLOW_LINKS));
        }
    }
}
