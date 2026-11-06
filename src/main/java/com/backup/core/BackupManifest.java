package com.backup.core;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 备份清单：一次快照中全部条目及其元数据的自描述文本（Properties 格式，version=2）。
 *
 * <p>清单是整个系统的"身份证"：归档内嵌一份（还原时先读它才能校验载荷）、
 * 目标目录旁挂一份（人工检视）、快照索引记录所有权。版本兼容：读入接受 1 和 2，
 * v1 缺失字段给保守默认值（时间缺省 null 表示"不发明"，绝不伪造时间戳）。</p>
 *
 * <p>写入三途径：{@link #toBytes()} 内嵌进归档、{@link #store(Path)} 原子落盘
 * （临时文件 + ATOMIC_MOVE）、{@link #fromString} 从归档流读回。加载侧所有解析异常
 * 统一包装为 IOException，并限制 entry.count ≤ 100 万（防恶意清单内存耗尽）。</p>
 */
public final class BackupManifest {
    public static final String ARCHIVE_ENTRY_NAME = "BACKPACK-MANIFEST.properties";
    private static final String VERSION = "2";

    private final String sourceRoot;
    private final Instant createdAt;
    private final CompressionType compressionType;
    private final boolean incremental;
    private final boolean encrypted;
    private final List<BackupEntry> entries;
    private BackupCatalog.Snapshot snapshot;

    public BackupManifest(
            String sourceRoot,
            Instant createdAt,
            CompressionType compressionType,
            boolean incremental,
            boolean encrypted,
            List<BackupEntry> entries
    ) {
        this.sourceRoot = Objects.requireNonNull(sourceRoot, "sourceRoot");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.compressionType = Objects.requireNonNull(compressionType, "compressionType");
        this.incremental = incremental;
        this.encrypted = encrypted;
        this.entries = List.copyOf(entries);
    }

    /** 挂接所有权快照（taskId/snapshotId/groupId/...），网络与淘汰流程据此校验身份。 */
    public BackupManifest withSnapshot(BackupCatalog.Snapshot value) {
        this.snapshot = value;
        return this;
    }

    /** 所有权快照（可能为 null：手工构造的清单未挂身份）。 */
    public BackupCatalog.Snapshot snapshot() { return snapshot; }

    /** ---- 以下是只读访问器（清单字段逐一暴露给服务层/网络层）---- */
    public String sourceRoot() {
        return sourceRoot;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public CompressionType compressionType() {
        return compressionType;
    }

    public boolean incremental() {
        return incremental;
    }

    public boolean encrypted() {
        return encrypted;
    }

    public List<BackupEntry> entries() {
        return entries;
    }

    /** 路径→条目视图：增量判定时作为 previous 查表。 */
    public Map<String, BackupEntry> entriesByPath() {
        return entries.stream().collect(Collectors.toMap(BackupEntry::relativePath, Function.identity(), (left, right) -> right));
    }

    /** 序列化为字符串（网络提交时内嵌于请求体）。 */
    public String toPropertiesString() throws IOException {
        Properties properties = toProperties();
        try (StringWriter writer = new StringWriter()) {
            properties.store(writer, "Backup manifest");
            return writer.toString();
        }
    }

    /** UTF-8 字节形态：ZIP/GZIP 内嵌与网络提交共用。 */
    public byte[] toBytes() throws IOException {
        return toPropertiesString().getBytes(StandardCharsets.UTF_8);
    }

    /** 原子落盘：临时文件写好后 ATOMIC_MOVE 替换目标，杜绝半份清单出现在磁盘上。 */
    public void store(Path manifestPath) throws IOException {
        Path target = manifestPath.toAbsolutePath().normalize();
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), ".manifest-", ".tmp");
        // 原子写三步：临时文件 → 写全文 → ATOMIC_MOVE；失败时 finally 清理临时文件。
        try {
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                toProperties().store(writer, "Backup manifest");
            }
            Files.move(temporary, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }

    /** 序列化为 Properties 文本；条目键前缀 entry.N. 保证顺序可读回。 */
    private Properties toProperties() {
        Properties properties = new Properties();
        properties.setProperty("version", VERSION);
        // 版本号是兼容契约：读侧接受 1/2，写侧恒为 2。
        properties.setProperty("sourceRoot", sourceRoot);
        // 头部字段：来源根（追溯用）、创建时刻、压缩/加密/增量标志。
        properties.setProperty("createdAt", createdAt.toString());
        properties.setProperty("compressionType", compressionType.name());
        properties.setProperty("incremental", Boolean.toString(incremental));
        properties.setProperty("encrypted", Boolean.toString(encrypted));
        properties.setProperty("entry.count", Integer.toString(entries.size()));
        if (snapshot != null) {
            properties.setProperty("taskId", snapshot.taskId());
            properties.setProperty("snapshotId", snapshot.snapshotId());
            properties.setProperty("groupId", snapshot.groupId());
            properties.setProperty("parentId", snapshot.parentId());
            properties.setProperty("sequence", Integer.toString(snapshot.sequence()));
            properties.setProperty("archiveName", snapshot.archiveName());
        }
        for (int i = 0; i < entries.size(); i++) {
        // 条目循环：键前缀 entry.N. 保证数组语义；时间可空（v1 兼容）。
            BackupEntry entry = entries.get(i);
            String prefix = "entry." + i + ".";
            properties.setProperty(prefix + "path", entry.relativePath());
        // 条目字段全套：路径/目录/尺寸/修改时间/哈希/权限/属主/组/类型/链接目标/访问·创建时间。
            properties.setProperty(prefix + "directory", Boolean.toString(entry.directory()));
            properties.setProperty(prefix + "size", Long.toString(entry.size()));
            properties.setProperty(prefix + "lastModifiedMillis", Long.toString(entry.lastModifiedMillis()));
            properties.setProperty(prefix + "sha256", entry.sha256());
            properties.setProperty(prefix + "permissions", entry.permissions());
            properties.setProperty(prefix + "owner", entry.owner());
            properties.setProperty(prefix + "group", entry.group());
            properties.setProperty(prefix + "type", entry.type().name());
            properties.setProperty(prefix + "linkTarget", entry.linkTarget());
            if (entry.lastAccessMillis() != null) properties.setProperty(prefix + "lastAccessMillis", entry.lastAccessMillis().toString());
            if (entry.creationMillis() != null) properties.setProperty(prefix + "creationMillis", entry.creationMillis().toString());
        }
        return properties;
    }

    /** 从旁挂文件加载；解析失败统一为 IOException。 */
    public static BackupManifest load(Path manifestPath) throws IOException {
        try (Reader reader = Files.newBufferedReader(manifestPath, StandardCharsets.UTF_8)) {
            try { return load(reader); }
            catch (IllegalArgumentException | NullPointerException e) { throw new IOException("Invalid backup manifest", e); }
        }
    }

    /** 从归档流中的清单文本加载（ZIP 首条目 / GZIP 头部段）。 */
    public static BackupManifest fromString(String content) throws IOException {
        try { return load(new StringReader(content)); }
        catch (IllegalArgumentException | NullPointerException e) { throw new IOException("Invalid backup manifest", e); }
    }

    /** 反序列化核心：版本校验（1/2）→ 数量界校验 → 逐条目重建 → 快照身份回挂。 */
    private static BackupManifest load(Reader reader) throws IOException {
        Properties properties = new Properties();
        properties.load(reader);
        String version = properties.getProperty("version");
        if (!VERSION.equals(version) && !"1".equals(version)) {
            throw new IOException("Unsupported manifest version: " + version);
        }
        int count = Integer.parseInt(properties.getProperty("entry.count", "0"));
        // 数量上限 100 万：恶意超大清单在分配内存前就被拒绝。
        if (count < 0 || count > 1_000_000) throw new IOException("Invalid manifest entry count");
        List<BackupEntry> entries = new ArrayList<>(count);
        // 逐条目重建：字段缺省值向 v1 兼容（type 按 directory 推导、时间可空）。
        for (int i = 0; i < count; i++) {
            String prefix = "entry." + i + ".";
            entries.add(new BackupEntry(
        // 读回侧：全部字段给缺省值——v1 清单缺字段也能还原（时间缺省不伪造）。
                    properties.getProperty(prefix + "path", ""),
                    Boolean.parseBoolean(properties.getProperty(prefix + "directory", "false")),
                    Long.parseLong(properties.getProperty(prefix + "size", "0")),
                    Long.parseLong(properties.getProperty(prefix + "lastModifiedMillis", "0")),
                    properties.getProperty(prefix + "sha256", ""),
                    properties.getProperty(prefix + "permissions", ""),
                    properties.getProperty(prefix + "owner", ""),
                    properties.getProperty(prefix + "group", ""),
                    EntryType.valueOf(properties.getProperty(prefix + "type",
                            Boolean.parseBoolean(properties.getProperty(prefix + "directory")) ? "DIRECTORY" : "FILE")),
                    properties.getProperty(prefix + "linkTarget", ""),
                    nullableLong(properties, prefix + "lastAccessMillis"),
                    nullableLong(properties, prefix + "creationMillis")
            ));
        }
        BackupManifest manifest = new BackupManifest(
        // 头部字段重建：版本校验已在上一步完成，缺省值仍保守。
                properties.getProperty("sourceRoot", ""),
                Instant.parse(properties.getProperty("createdAt", Instant.EPOCH.toString())),
                CompressionType.valueOf(properties.getProperty("compressionType", CompressionType.ZIP.name())),
                Boolean.parseBoolean(properties.getProperty("incremental", "false")),
                Boolean.parseBoolean(properties.getProperty("encrypted", "false")),
                entries
        );
        if (properties.containsKey("snapshotId")) {
        // 快照身份回挂：存在才挂——v1 归档没有身份概念，兼容读取不伪造。
            manifest.withSnapshot(new BackupCatalog.Snapshot(properties.getProperty("taskId"),
                    properties.getProperty("snapshotId"), properties.getProperty("groupId"),
                    properties.getProperty("parentId", ""), Integer.parseInt(properties.getProperty("sequence")),
                    properties.getProperty("archiveName"), manifest.createdAt()));
        }
        return manifest;
    }

    /** 可空整数读取：v1 清单缺时间字段时返回 null（语义=不发明时间）。 */
    private static Long nullableLong(Properties properties, String key) {
        String value = properties.getProperty(key);
        return value == null ? null : Long.valueOf(value);
    }
}
