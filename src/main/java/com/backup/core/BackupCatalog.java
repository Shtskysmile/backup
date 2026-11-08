package com.backup.core;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;

/**
 * 归档的所有权目录：负责把"一次快照"发布为磁盘上可审计的三件套
 * （归档 + 清单旁挂 + 快照索引），并在保留策略下整组淘汰旧快照。
 *
 * <p>核心不变量：</p>
 * <ul>
 *   <li>发布前 verifyPair——归档与清单必须成对存在且六个身份字段一致；</li>
 *   <li>淘汰以"组"为单位（1 全量 + ≤9 增量），先 validateChain 验证序列连续、
 *       父链无分叉，再逐条 verifyPair 后从尾向根删除；</li>
 *   <li>不可信状态一律保守处理：损坏索引使整轮清理放弃，无效组保留并警告——
 *       宁可少删，不可错删；</li>
 *   <li>目录中其他任务、无关文件、未知记录永远不碰；不凭扩展名猜测归属。</li>
 * </ul>
 *
 * <p>该类同时被本地 BackupService 与网络 NetworkBackupServer 复用，
 * 服务器端保留因此与本地语义逐字节一致。</p>
 */
public final class BackupCatalog {
    private static final String INDEX = ".snapshot.properties";
    private static final String MANIFEST = ".manifest.properties";
    private BackupCatalog() {}

    public record Snapshot(String taskId, String snapshotId, String groupId, String parentId,
                           int sequence, String archiveName, Instant createdAt) {}

    /**
     * 发布快照三件套的最后一环：先 verifyPair（归档+清单已就位且身份一致），
     * 再把索引写入 .snapshot.properties（临时文件 + ATOMIC_MOVE，防半份索引）。
     */
    public static void publish(Path destination, Snapshot snapshot) throws IOException {
        validate(snapshot);
        Path directory = directory(destination);
        verifyPair(directory, snapshot);
        Path target = directory.resolve(snapshot.archiveName() + INDEX);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !regular(target))
            throw new IOException("Unsafe catalog entry: " + target);
        Path temporary = Files.createTempFile(directory, ".snapshot-", ".tmp");
        try {
            try (Writer out = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                properties(snapshot).store(out, "Completed backup snapshot");
            }
            // 永不暴露写了一半的索引：即便文件系统不支持原子移动，也要先写临时文件再换名。
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /**
     * 组保留淘汰。keepGroups=0 直接返回（不关闭十次轮换语义）；正数保留
     * 最新 N 个完整有效组，且含全目录最新快照的组永远受保护。
     */
    public static void prune(Path destination, String taskId, int keepGroups, Consumer<String> warnings)
            throws IOException {
        identifier(taskId); Objects.requireNonNull(warnings, "warnings");
        if (keepGroups < 0) throw new IllegalArgumentException("keepGroups must be nonnegative");
        if (keepGroups == 0) return;
        Path directory = directory(destination);
        List<Snapshot> snapshots = new ArrayList<>();
        boolean corrupt = false;
        try (var files = Files.list(directory)) {
            for (Path file : files.filter(p -> p.getFileName().toString().endsWith(INDEX)).toList()) {
                try {
                    // 单个索引损坏不立即失败：先记警告继续扫描；若本轮出现任何损坏记录，
                    // 则整体放弃清理——不可信记录可能属于任何组，猜测即风险。
                    if (!regular(file)) throw new IOException("Not a regular index");
                    Properties p = read(file);
                    if (!"1".equals(p.getProperty("version"))) throw new IOException("Unknown index version");
                    Snapshot s = parse(p); validate(s);
                    if (!file.getFileName().toString().equals(s.archiveName() + INDEX))
                        throw new IOException("Index filename mismatch");
                    snapshots.add(s);
                } catch (IOException | RuntimeException ex) {
                    warnings.accept("Preserving catalog with invalid entry " + file.getFileName() + ": " + ex.getMessage());
                    corrupt = true;
                }
            }
        }
        // 一条读不出的记录可能属于任何组：绝不猜测它拥有哪些档案——这是保守原则的根基。
        if (corrupt) return;
        Map<String, List<Snapshot>> groups = new HashMap<>();
        // 按组聚合 → 逐组验链 → 有效组按时间倒序 → 从第 N+1 个组开始删（保护最新组）。
        Set<String> duplicateIds = new HashSet<>();
        Set<String> ids = new HashSet<>();
        for (Snapshot s : snapshots) {
            String key = s.taskId() + "/" + s.snapshotId();
            if (!ids.add(key)) duplicateIds.add(key);
            if (s.taskId().equals(taskId)) groups.computeIfAbsent(s.groupId(), k -> new ArrayList<>()).add(s);
        }
        List<List<Snapshot>> valid = new ArrayList<>();
        for (var members : groups.values()) {
        // 组校验：链不完整/分叉/缺依赖/身份对不上 → 整组保留并警告（永不误删）。
            members.sort(Comparator.comparingInt(Snapshot::sequence));
            try {
                validateChain(members);
                for (Snapshot s : members) {
                    if (duplicateIds.contains(s.taskId() + "/" + s.snapshotId()))
                        throw new IOException("Duplicate snapshot identity");
                    verifyPair(directory, s);
                }
                valid.add(members);
            } catch (IOException | RuntimeException ex) {
                warnings.accept("Preserving invalid group " + members.getFirst().groupId() + ": " + ex.getMessage());
            }
        }
        // 有效组按"全量根的创建时间"倒序：新的在前，keepGroups 之后的就是待删组。
        Comparator<Snapshot> chronological = Comparator.comparing(Snapshot::createdAt)
                .thenComparing(Snapshot::groupId).thenComparing(Snapshot::snapshotId);
        valid.sort((a, b) -> chronological.compare(b.getFirst(), a.getFirst()));
        Snapshot latest = snapshots.stream().filter(s -> s.taskId.equals(taskId)).max(chronological).orElse(null);
        for (int i = keepGroups; i < valid.size(); i++) {
            List<Snapshot> members = valid.get(i);
            // 最新快照所在组永不受本轮清理影响（哪怕已超出 keepGroups）。
            if (latest != null && members.getFirst().groupId().equals(latest.groupId())) continue;
            for (int j = members.size() - 1; j >= 0; j--) {
                Snapshot s = members.get(j);
                try {
                    // 删除前重读索引核对身份（防 TOCTOU：扫描后文件被外部改动）。
                    verifyPair(directory, s);
                    Path index = directory.resolve(s.archiveName() + INDEX);
                    if (!regular(index) || !s.equals(parse(read(index))))
                        throw new IOException("Index changed during retention");
                    Files.delete(directory.resolve(s.archiveName()));
                    Files.delete(directory.resolve(s.archiveName() + MANIFEST));
                    Files.delete(index);
                } catch (IOException | RuntimeException ex) {
                    // 单条失败即停止本组：保留剩余祖先链，避免"删一半"的不完整历史。
                    warnings.accept("Stopped pruning group " + s.groupId() + ": " + ex.getMessage());
                    break;
                }
            }
        }
    }

    /** 链校验：根必须 sequence=1；序列连续 1..n 且每个后继的 parentId 指向前一快照（无分叉）。 */
    private static void validateChain(List<Snapshot> members) throws IOException {
        Snapshot root = members.getFirst();
        if (root.sequence() != 1) throw new IOException("Missing full root");
        for (int i = 0; i < members.size(); i++) {
            Snapshot s = members.get(i);
            if (s.sequence() != i + 1 || (i > 0 && !s.parentId().equals(members.get(i - 1).snapshotId())))
                throw new IOException("Incomplete or branched chain");
        }
    }

    /** 目标目录安全校验：拒绝 ".." "." 组件、必须是常规目录，返回真实路径。 */
    private static Path directory(Path path) throws IOException {
        Objects.requireNonNull(path, "destination");
        for (Path part : path) if (part.toString().equals("..") || part.toString().equals("."))
            throw new IllegalArgumentException("Destination contains traversal");
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Destination is not a regular directory");
        return path.toRealPath();
    }

    /** 常规文件判定（NOFOLLOW）：目录里出现同名链接一律按不安全处理。 */
    private static boolean regular(Path path) { return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS); }
    /** 读取 Properties 文本（索引/清单共用）。 */
    private static Properties read(Path path) throws IOException {
        Properties p = new Properties();
        try (Reader in = Files.newBufferedReader(path, StandardCharsets.UTF_8)) { p.load(in); }
        return p;
    }
    /** 发布前/删除前的成对校验：归档与清单都是非链接常规文件，且清单 version=2、六个身份字段与索引一致。 */
    private static void verifyPair(Path directory, Snapshot s) throws IOException {
        Path archive = directory.resolve(s.archiveName()), manifest = directory.resolve(s.archiveName() + MANIFEST);
        if (!regular(archive) || !regular(manifest)) throw new IOException("Missing or unsafe archive/manifest pair");
        Properties p = read(manifest);
        if (!"2".equals(p.getProperty("version"))) throw new IOException("Unsupported manifest version");
        Properties expected = properties(s);
        for (String key : List.of("taskId", "snapshotId", "groupId", "parentId", "sequence", "archiveName"))
            if (!expected.getProperty(key).equals(p.getProperty(key))) throw new IOException("Manifest identity mismatch: " + key);
    }
    /** 标识符白名单（taskId/snapshotId/groupId 共用）：字母数字开头，限 200 字符，杜绝路径注入。 */
    private static void identifier(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,199}"))
            throw new IllegalArgumentException("Unsafe identifier");
    }
    /** 快照身份的结构校验：序列 1..10、全量根身份规则、归档文件名防注入（无路径分隔/非绝对/无 NUL）。 */
    private static void validate(Snapshot s) {
        Objects.requireNonNull(s, "snapshot");
        identifier(s.taskId());identifier(s.snapshotId());identifier(s.groupId());
        Objects.requireNonNull(s.parentId(), "parentId");Objects.requireNonNull(s.createdAt(), "createdAt");
        if (s.sequence() < 1 || s.sequence() > 10) throw new IllegalArgumentException("Sequence must be 1..10");
        if (s.sequence() == 1) {
            if (!s.parentId().isEmpty() || !s.snapshotId().equals(s.groupId())) throw new IllegalArgumentException("Invalid full root");
        } else { identifier(s.parentId()); if (s.snapshotId().equals(s.parentId()) || s.snapshotId().equals(s.groupId())) throw new IllegalArgumentException("Invalid parent identity"); }
        String name = s.archiveName();
        if (name == null || name.isBlank() || name.equals(".") || name.equals("..") || name.contains("/") || name.contains("\\") || name.indexOf('\0') >= 0 || Path.of(name).isAbsolute())
            throw new IllegalArgumentException("Unsafe archive filename");
    }
    /** 快照 → 索引属性（version=1 固定，字段与 parse 互逆）。 */
    private static Properties properties(Snapshot s) {
        Properties p = new Properties();p.setProperty("version", "1");
        p.setProperty("taskId", s.taskId());p.setProperty("snapshotId", s.snapshotId());
        p.setProperty("groupId", s.groupId());p.setProperty("parentId", s.parentId());
        p.setProperty("sequence", Integer.toString(s.sequence()));p.setProperty("archiveName", s.archiveName());
        p.setProperty("createdAt", s.createdAt().toString());return p;
    }
    /** 索引属性 → 快照身份；createdAt 解析失败抛给上层按损坏处理。 */
    private static Snapshot parse(Properties p) {
        return new Snapshot(p.getProperty("taskId"), p.getProperty("snapshotId"), p.getProperty("groupId"),
                p.getProperty("parentId"), Integer.parseInt(p.getProperty("sequence")),
                p.getProperty("archiveName"), Instant.parse(p.getProperty("createdAt")));
    }
}
