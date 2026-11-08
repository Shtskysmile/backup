package com.backup.io;

import com.backup.core.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import java.security.*;
import java.util.function.Consumer;
import java.util.zip.*;

/**
 * 归档读写层：把"清单 + 条目载荷"写入 ZIP/GZIP 容器，并把容器安全地还原回目录树。
 *
 * <p>写侧不变量：清单永远是 ZIP 的第一个条目 / GZIP 容器的头部——还原侧在解析任何
 * 用户载荷之前先拿到清单并完成校验；FILE 条目边拷贝边算 SHA-256，扫描与打包之间
 * 源文件被篡改（哪怕同尺寸）都会拒绝发布。</p>
 *
 * <p>读侧安全四层：格式魔数识别（PK / 1F 8B / GZIP 容器魔数）→ validateManifest
 * 结构校验（穿越/绝对路径/重复路径/设备号白名单/非目录祖先）→ restorePayload
 * 逐条目安全写入（临时文件原子替换、FIFO 不打开流、设备尽力重建）→ finish 阶段
 * 重建链接并按目录深度从深到浅回填元数据。</p>
 *
 * <p>所有长度字段（清单长度、字符串长度、载荷字节数）都有上下界校验——
 * 归档必须被当作不可信输入对待。</p>
 */
public final class ArchiveService {
    /** ZIP 内用户数据的统一前缀：名字空间上隔离元数据与数据，非该前缀的条目一律拒绝。 */
    private static final String ZIP_DATA_PREFIX = "data/";
    /** GZIP 容器魔数（v2）；读取侧同时接受 v1 以兼容旧归档。 */
    private static final String GZIP_MAGIC = "BACKPACK-GZIP-2";
    private final FifoSupport fifoSupport;
    private final SymbolicLinkPermissions symbolicLinkPermissions = new SymbolicLinkPermissions();
    /** 产品默认构造。 */
    public ArchiveService() { this(new FifoSupport()); }
    /** 测试注入点：可替换 mkfifo/touch 实现以模拟失败与假成功路径。 */
    ArchiveService(FifoSupport fifoSupport) { this.fifoSupport = Objects.requireNonNull(fifoSupport); }

    /** 清单长度上限 64MB：正常清单远小于此，恶意超长直接拒绝。 */
    private static final int MAX_MANIFEST = 64 * 1024 * 1024;

    /** ZIP 写入：清单顶置 + data/ 前缀载荷；目录条目名以 / 结尾，特殊文件为空载荷条目。 */
    public void writeZipArchive(Path sourceRoot, Path output, BackupManifest manifest, Iterable<BackupEntry> archivedEntries) throws IOException {
        Files.createDirectories(output.getParent());
        try (ZipOutputStream out = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(output)))) {
            // 不变量：清单必须是第一个条目——还原侧先读清单才能校验后续一切载荷。
            out.putNextEntry(new ZipEntry(BackupManifest.ARCHIVE_ENTRY_NAME)); out.write(manifest.toBytes()); out.closeEntry();
            for (BackupEntry entry : archivedEntries) {
                // 目录条目名以 / 结尾（ZIP 目录语义）；特殊文件为空载荷条目占位，保持结构完整。
                ZipEntry zip = new ZipEntry(ZIP_DATA_PREFIX + entry.relativePath() + (entry.directory() ? "/" : ""));
                zip.setLastModifiedTime(FileTime.fromMillis(entry.lastModifiedMillis()));
                out.putNextEntry(zip);
                if (entry.type() == EntryType.FILE) copySource(sourceRoot, entry, out);
                out.closeEntry();
            }
        }
    }

    /** GZIP 自定义容器：魔数 + 清单长度 + 清单 + 条目数 + 逐条目（路径/目录标志/尺寸/修改时间/载荷）。 */
    public void writeGzipArchive(Path sourceRoot, Path output, BackupManifest manifest, Iterable<BackupEntry> archivedEntries) throws IOException {
        Files.createDirectories(output.getParent());
        List<BackupEntry> entries = new ArrayList<>(); archivedEntries.forEach(entries::add);
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(output))))) {
            // 自定义容器布局：魔数 → 清单长度 + 清单全文 → 条目数 → 逐条目（元组 + 可选载荷）。
            writeString(out, GZIP_MAGIC); byte[] bytes = manifest.toBytes(); out.writeInt(bytes.length); out.write(bytes); out.writeInt(entries.size());
            for (BackupEntry entry : entries) {
                writeString(out, entry.relativePath()); out.writeBoolean(entry.directory());
                out.writeLong(entry.type() == EntryType.FILE ? entry.size() : 0); out.writeLong(entry.lastModifiedMillis());
                if (entry.type() == EntryType.FILE) copySource(sourceRoot, entry, out);
            }
        }
    }

    /**
     * 一致性守卫：边拷贝边算 SHA-256，写完后比对字节数与扫描期记录的哈希。
     * 防止"扫描→打包"时间窗内源文件被改（尤其同尺寸篡改），宁可备份失败也不发布坏归档。
     */
    private void copySource(Path root, BackupEntry entry, OutputStream out) throws IOException {
        Path path = root.resolve(entry.relativePath());
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Source changed type: " + path);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new DigestInputStream(Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS), digest)) {
                long written = in.transferTo(out);
                if (written != entry.size() || (!entry.sha256().isEmpty()
                        && !HexFormat.of().formatHex(digest.digest()).equals(entry.sha256()))) {
                    throw new IOException("Source changed while backing up: " + path);
                }
            }
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    /** 兼容入口：只要清单的旧调用。 */
    public BackupManifest restore(Path archive, Path destination) throws IOException { return restoreDetailed(archive, destination).manifest(); }

    /**
     * 还原入口：识别格式 → 分派到 ZIP/GZIP 读取器。
     * 还原目标若是符号链接直接拒绝（防止借目标链接把内容写到目录外）。
     */
    public RestoreResult restoreDetailed(Path archive, Path destination) throws IOException {
        Path root = destination.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(root)) throw new IOException("Restore destination is a symbolic link: " + root);
        Files.createDirectories(root); root = root.toRealPath();
        byte[] header;
        // 魔数识别：'P''K' → ZIP；0x1F 0x8B → GZIP；其余一律拒绝（哪怕扩展名是 .zip）。
        try (InputStream in = Files.newInputStream(archive)) { header = in.readNBytes(4); }
        if (header.length >= 2 && header[0] == 'P' && header[1] == 'K') return restoreZip(archive, root);
        if (header.length >= 2 && (header[0] & 255) == 31 && (header[1] & 255) == 139) return restoreGzip(archive, root);
        throw new IOException("Unsupported archive type: " + archive);
    }

    /** ZIP 读取：首条目必须是清单，随后逐条目校验（类型匹配、无多余数据）并写载荷。 */
    private RestoreResult restoreZip(Path archive, Path root) throws IOException {
        BackupManifest manifest;
        List<BackupEntry> restored = new ArrayList<>(); Set<String> seen = new HashSet<>();
        List<String> warnings = new ArrayList<>(); Set<String> skipped = new HashSet<>();
        try (ZipInputStream in = new ZipInputStream(new BufferedInputStream(Files.newInputStream(archive)))) {
            ZipEntry first = in.getNextEntry();
            if (first == null || !BackupManifest.ARCHIVE_ENTRY_NAME.equals(first.getName())) throw new IOException("Archive must start with a Backup manifest");
            manifest = BackupManifest.fromString(new String(readBounded(in), StandardCharsets.UTF_8));
            // 先全量校验清单结构，再消费任何载荷——恶意清单在写入第一个字节前就被拒绝。
            Map<String, BackupEntry> byPath = validateManifest(root, manifest);
            in.closeEntry(); ZipEntry zip;
            while ((zip = in.getNextEntry()) != null) {
                if (!zip.getName().startsWith(ZIP_DATA_PREFIX)) throw new IOException("Unexpected archive entry: " + zip.getName());
                String path = zip.getName().substring(ZIP_DATA_PREFIX.length());
                if (path.endsWith("/")) path = path.substring(0, path.length() - 1);
                BackupEntry entry = requireEntry(byPath, seen, path);
                // 目录标志一致性：ZIP 条目的目录语义必须与清单声明吻合。
                if (zip.isDirectory() != entry.directory()) throw new IOException("Entry type mismatch: " + path);
                restorePayload(root, entry, in, entry.type() == EntryType.FILE ? entry.size() : 0, warnings, skipped);
                // 条目后必须立即流结束——防止归档夹带清单之外的数据。
                if (in.read() != -1) throw new IOException("Unexpected extra entry data: " + path);
                restored.add(entry); in.closeEntry();
            }
        }
        return finish(root, manifest, restored, warnings, skipped);
    }

    /** GZIP 读取：魔数（兼容 v1）→ 清单长度界校验 → 逐条目元数据比对 → 载荷。尾部多余数据视为损坏。 */
    private RestoreResult restoreGzip(Path archive, Path root) throws IOException {
        BackupManifest manifest; List<BackupEntry> restored = new ArrayList<>(); Set<String> seen = new HashSet<>();
        List<String> warnings = new ArrayList<>(); Set<String> skipped = new HashSet<>();
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(archive))))) {
            String magic = readString(in);
            if (!GZIP_MAGIC.equals(magic) && !"BACKPACK-GZIP-1".equals(magic)) throw new IOException("Unsupported Backup GZIP archive");
            int length = in.readInt(); if (length <= 0 || length > MAX_MANIFEST) throw new IOException("Invalid manifest length");
            byte[] bytes = in.readNBytes(length); if (bytes.length != length) throw new EOFException("Truncated manifest");
            manifest = BackupManifest.fromString(new String(bytes, StandardCharsets.UTF_8));
            Map<String, BackupEntry> byPath = validateManifest(root, manifest);
            int count = in.readInt(); if (count < 0 || count > byPath.size()) throw new IOException("Invalid archive entry count");
            // 逐条目：路径 → 目录标志/尺寸与清单比对（防清单与载荷不一致）→ 精确读取载荷。
            for (int i = 0; i < count; i++) {
                BackupEntry entry = requireEntry(byPath, seen, readString(in));
                boolean directory = in.readBoolean(); long size = in.readLong(); in.readLong();
                long expected = entry.type() == EntryType.FILE ? entry.size() : 0;
                if (directory != entry.directory() || size != expected) throw new IOException("Entry metadata mismatch: " + entry.relativePath());
                restorePayload(root, entry, in, size, warnings, skipped); restored.add(entry);
            }
            if (in.read() != -1) throw new IOException("Unexpected trailing archive data");
        }
        return finish(root, manifest, restored, warnings, skipped);
    }

    /**
     * 清单结构校验（在任何载荷写入之前完成）：路径穿越与绝对路径拒绝（safeResolve）、
     * 重复路径拒绝、链接必须带目标、设备号白名单、FIFO/套接字必须零载荷、
     * 条目的目录祖先必须是目录、硬链接目标必须存在且为文件。
     */
    private Map<String, BackupEntry> validateManifest(Path root, BackupManifest manifest) throws IOException {
        Map<String, BackupEntry> result = new LinkedHashMap<>();
        for (BackupEntry entry : manifest.entries()) {
            safeResolve(root, entry.relativePath());
            if (entry.type() == EntryType.FIFO && (entry.size() != 0 || !entry.linkTarget().isEmpty())) throw new IOException("FIFO entries must have zero payload and no target");
            if ((entry.type() == EntryType.CHARACTER_DEVICE || entry.type() == EntryType.BLOCK_DEVICE)
                    && (entry.size() != 0 || !entry.linkTarget().matches("[0-9]{1,5}:[0-9]{1,5}"))) throw new IOException("Invalid device entry: " + entry.relativePath());
            if (entry.type() == EntryType.SOCKET && (entry.size() != 0 || !entry.linkTarget().isEmpty())) throw new IOException("Socket entries must have zero payload and no target");
            if (result.put(entry.relativePath(), entry) != null) throw new IOException("Duplicate manifest path: " + entry.relativePath());
            if ((entry.type() == EntryType.HARD_LINK || entry.type() == EntryType.SYMBOLIC_LINK) && entry.linkTarget().isEmpty()) throw new IOException("Missing link target");
        }
        for (BackupEntry entry : result.values()) {
            Path parent = Path.of(entry.relativePath()).getParent();
            while (parent != null) {
                BackupEntry ancestor = result.get(parent.toString().replace('\\', '/'));
                if (ancestor != null && !ancestor.directory()) throw new IOException("Non-directory archive ancestor: " + parent);
                parent = parent.getParent();
            }
            if (entry.type() == EntryType.HARD_LINK) {
                safeResolve(root, entry.linkTarget()); BackupEntry target = result.get(entry.linkTarget());
                if (target == null || target.type() != EntryType.FILE) throw new IOException("Invalid hardlink target: " + entry.linkTarget());
            }
        }
        return result;
    }

    /** 条目查找 + 已见集合防重（同一载荷路径出现两次即判定为伪造归档）。 */
    private BackupEntry requireEntry(Map<String, BackupEntry> entries, Set<String> seen, String path) throws IOException {
        BackupEntry entry = entries.get(path);
        if (entry == null || !seen.add(path)) throw new IOException("Unknown or duplicate archive path: " + path);
        return entry;
    }

    /**
     * 单条目载荷写入，按类型分派：目录 mkdir（先清掉同名非目录）；常规文件走
     * 临时文件 + SHA-256 校验 + 原子替换（不截断外部硬链接）；FIFO/设备/套接字
     * 走特殊文件路径，失败进 skipped 集合（还原整体继续，仅记录警告）。
     */
    private void restorePayload(Path root, BackupEntry entry, InputStream in, long size, List<String> warnings, Set<String> skipped) throws IOException {
        Path out = safeResolve(root, entry.relativePath()); checkParents(root, out);
        if (entry.directory()) {
            if (Files.isSymbolicLink(out)) throw new IOException("Symbolic link in restore path: " + out);
            if (Files.exists(out, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(out, LinkOption.NOFOLLOW_LINKS)) Files.delete(out);
            Files.createDirectories(out);
        } else if (entry.type() == EntryType.FILE) {
            if (Files.isSymbolicLink(out)) throw new IOException("Refusing to overwrite symbolic link: " + out);
            Files.createDirectories(out.getParent());
            Path staged = Files.createTempFile(out.getParent(), ".backup-restore-", ".tmp");
            try {
                try (OutputStream output = Files.newOutputStream(staged)) { copyExactly(in, output, size); }
                if (!entry.sha256().isEmpty() && !HashUtils.sha256(staged).equals(entry.sha256())) throw new IOException("Content checksum mismatch: " + entry.relativePath());
        // 落盘前校验：临时文件哈希必须等于清单声明——防归档位翻转/中间人篡改。
                moveReplacing(staged, out);
            } finally { Files.deleteIfExists(staged); }
        // 链接条目（SYMBOLIC/HARD）没有载荷，在 finish() 阶段统一重建——此处直接跳过。
        } else if (entry.type() == EntryType.FIFO && !restoreFifo(root, entry, warnings::add)) {
            skipped.add(entry.relativePath());
        } else if ((entry.type() == EntryType.CHARACTER_DEVICE || entry.type() == EntryType.BLOCK_DEVICE
                || entry.type() == EntryType.SOCKET) && !restoreDevice(root, entry, warnings::add)) {
            skipped.add(entry.relativePath());
        }
    }

    /**
     * 设备/套接字处理：套接字只记录不重建（无监听进程的 socket 文件无意义）；
     * 设备用 mknod c/b 主 次 重建（通常需要 root），并以 isSpecialType 复核类型位，
     * 防 mknod 假成功。无工具/无权限 → 警告 + skipped，不影响其余条目。
     */
    private boolean restoreDevice(Path root, BackupEntry entry, Consumer<String> warnings) throws IOException {
        Path out = safeResolve(root, entry.relativePath()); checkParents(root, out);
        if (Files.isSymbolicLink(out)) throw new IOException("Refusing to overwrite symbolic link: " + out);
        if (entry.type() == EntryType.SOCKET) {
            warnings.accept(entry.relativePath() + ": unix domain sockets cannot be recreated by a backup tool");
            return false;
        }
        Files.createDirectories(out.getParent());
        Path staged = Files.createTempFile(out.getParent(), ".backup-device-", ".tmp");
        Files.delete(staged);
        try {
            String[] parts = entry.linkTarget().split(":", 2);
            String kind = entry.type() == EntryType.CHARACTER_DEVICE ? "c" : "b";
            Process process = new ProcessBuilder("mknod", staged.toString(), kind, parts[0], parts[1])
                    .redirectErrorStream(true).start();
            try (InputStream output = process.getInputStream()) { output.readAllBytes(); }
            boolean created = process.waitFor() == 0 && isSpecialType(staged, entry.type());
            if (!created) throw new IOException("mknod did not create " + entry.type());
            moveReplacing(staged, out);
            return true;
        } catch (IOException | UnsupportedOperationException | IllegalArgumentException e) {
            warnings.accept(entry.relativePath() + ": " + entry.type() + " not recreated (" + e.getMessage() + ")");
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            warnings.accept(entry.relativePath() + ": " + entry.type() + " not recreated (interrupted)");
            return false;
        } finally { Files.deleteIfExists(staged); }
    }

    /** 用 unix:mode 类型位复核暂存文件类型与期望一致（FIFO/设备/套接字各自的八进制类型码）。 */
    private static boolean isSpecialType(Path path, EntryType expected) {
        try {
            Object value = Files.getAttribute(path, "unix:mode", LinkOption.NOFOLLOW_LINKS);
            if (!(value instanceof Integer mode)) return false;
            return switch (mode & 0170000) {
                case 0010000 -> expected == EntryType.FIFO;
                case 0020000 -> expected == EntryType.CHARACTER_DEVICE;
                case 0060000 -> expected == EntryType.BLOCK_DEVICE;
                case 0140000 -> expected == EntryType.SOCKET;
                default -> false;
            };
        } catch (IOException | UnsupportedOperationException | IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * FIFO 重建：mkfifo -m 权限 → isFifo 属性验证（防假成功）→ 原子移动到位。
     * 全程不打开管道数据流（打开读端会阻塞等待写者）。失败保留已存在的同名目标并返回 false。
     */
    private boolean restoreFifo(Path root, BackupEntry entry, Consumer<String> warnings) throws IOException {
        Path out = safeResolve(root, entry.relativePath()); checkParents(root, out);
        if (Files.isSymbolicLink(out)) throw new IOException("Refusing to overwrite symbolic link: " + out);
        Files.createDirectories(out.getParent());
        Path staged = Files.createTempFile(out.getParent(), ".backup-fifo-", ".tmp");
        Files.delete(staged);
        try {
            fifoSupport.create(entry, staged);
            moveReplacing(staged, out);
            return true;
        } catch (IOException | UnsupportedOperationException | IllegalArgumentException e) {
            warnings.accept(entry.relativePath() + ": FIFO not recreated (" + e.getMessage() + ")");
            return false;
        } finally { Files.deleteIfExists(staged); }
    }

    /**
     * 收尾三件事：
     * ① 全量归档完整性——清单条目数必须与已还原条目数一致（缺载荷即失败）；
     * ② 链接重建——硬链接先校验基文件存在且哈希一致，软链接直接重建；两阶段设计
     *    让链接永远指向"已还原完成"的目标；
     * ③ 元数据回填——增量先做 baselineMatches 内容核验（防止把 A 快照属性写到 B 内容上），
     *    skipped 条目不回填；按路径深度从深到浅（先子后父，避免父目录权限收缩挡住子项）。
     */
    private RestoreResult finish(Path root, BackupManifest manifest, List<BackupEntry> entries, List<String> warnings, Set<String> skipped) throws IOException {
        // 全量完整性检查：清单里的每个条目都必须有对应载荷（特殊文件计入条目、失败进 skipped 也算已处理）。
        if (!manifest.incremental() && entries.size() != manifest.entries().size()) {
            throw new IOException("Full archive is missing file or directory payload entries");
        }
        for (EntryType type : List.of(EntryType.HARD_LINK, EntryType.SYMBOLIC_LINK)) {
            // 链接重建：暂存名创建 → moveReplacing 原子上位；硬链接基文件缺失或哈希不符则整体失败。
            for (BackupEntry entry : entries) if (entry.type() == type) {
                Path out = safeResolve(root, entry.relativePath()); checkParents(root, out); Files.createDirectories(out.getParent());
                Path staged = Files.createTempFile(out.getParent(), ".backup-link-", ".tmp");
                Files.delete(staged);
                try {
                    if (type == EntryType.HARD_LINK) {
                        Path target = safeResolve(root, entry.linkTarget()); checkParents(root, target);
                        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Hardlink base missing; restore full backup first: " + entry.linkTarget());
                        BackupEntry expected = manifest.entriesByPath().get(entry.linkTarget());
                        if (!expected.sha256().isEmpty() && !HashUtils.sha256(target).equals(expected.sha256())) throw new IOException("Hardlink base content mismatch: " + entry.linkTarget());
                        Files.createLink(staged, target);
                        // 硬链接重建：staged 名先建链再移动，避免直接对目标做链接操作。
                    } else Files.createSymbolicLink(staged, Path.of(entry.linkTarget()));
                    moveReplacing(staged, out);
                } catch (UnsupportedOperationException | SecurityException | InvalidPathException e) {
                    throw new IOException("Cannot restore link: " + entry.relativePath(), e);
                } finally { Files.deleteIfExists(staged); }
            }
        }
        List<BackupEntry> metadata = new ArrayList<>(entries.stream().filter(entry -> !skipped.contains(entry.relativePath())).toList());
        // 增量回填：未出现在本次归档中的清单条目，先核验基线内容归属，不符只警告。
        if (manifest.incremental()) {
            Set<String> restoredPaths = new HashSet<>();
            entries.forEach(entry -> restoredPaths.add(entry.relativePath()));
            for (BackupEntry entry : manifest.entries()) if (!restoredPaths.contains(entry.relativePath())) {
                if (baselineMatches(root, entry)) metadata.add(entry);
                else warnings.add(entry.relativePath() + ": incremental baseline missing or different; restore the full backup and preceding increments first");
            }
        }
        metadata.sort(Comparator.comparingInt((BackupEntry entry) -> Path.of(entry.relativePath()).getNameCount()).reversed());
        // 深度排序（深→浅）：先固定子项，最后才设置可能收紧访问的父目录权限。
        for (BackupEntry entry : metadata) applyMetadata(root, entry, warnings::add);
        return new RestoreResult(manifest, warnings);
    }

    /** 增量基线核验：按类型比对已还原内容与清单声明，防属性误写到不相符对象。 */
    private boolean baselineMatches(Path root, BackupEntry entry) throws IOException {
        Path path = safeResolve(root, entry.relativePath()); checkParents(root, path);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return false;
        return switch (entry.type()) {
            case DIRECTORY -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
            case SYMBOLIC_LINK -> Files.isSymbolicLink(path) && Files.readSymbolicLink(path).toString().equals(entry.linkTarget());
            case FILE -> matchesFile(path, entry);
            case FIFO -> FifoSupport.isFifo(path);
            case CHARACTER_DEVICE, BLOCK_DEVICE, SOCKET -> isSpecialType(path, entry.type());
            case HARD_LINK -> {
                Path target = safeResolve(root, entry.linkTarget()); checkParents(root, target);
                yield matchesFile(path, entry) && Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) && Files.isSameFile(path, target);
            }
        };
    }

    /** 常规文件基线比对：存在 + 尺寸一致 + 哈希一致（空哈希视为不可比对，返回 false 保守处理）。 */
    private boolean matchesFile(Path path, BackupEntry entry) throws IOException {
        return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files.size(path) == entry.size()
                && !entry.sha256().isEmpty() && HashUtils.sha256(path).equals(entry.sha256());
    }

    /**
     * 元数据尽力回填：属主/组/权限走 POSIX 视图（无视图或无权限 → 警告不失败）；
     * 时间走 BasicFileAttributeView；创建时间单独设置（平台支持度最差）。
     * 符号链接特殊处理：属性先比对（一致则不动作），macOS 才尝试 chmod -h 恢复链接自身权限。
     */
    private void applyMetadata(Path root, BackupEntry entry, Consumer<String> warnings) throws IOException {
        Path out = safeResolve(root, entry.relativePath()); checkParents(root, out);
        boolean symlink = entry.type() == EntryType.SYMBOLIC_LINK;
        boolean fifo = entry.type() == EntryType.FIFO;
        if (fifo && !FifoSupport.isFifo(out)) { warnings.accept(entry.relativePath() + ": FIFO metadata skipped because target has a different type"); return; }
        if (fifo && !entry.permissions().isBlank()) {
            try {
                if (!Files.getPosixFilePermissions(out, LinkOption.NOFOLLOW_LINKS).equals(PosixFilePermissions.fromString(entry.permissions()))
                        && !restoreFifo(root, entry, warnings)) return;
            } catch (IOException | UnsupportedOperationException | IllegalArgumentException e) {
                warnings.accept(entry.relativePath() + ": could not restore FIFO permissions (" + e.getMessage() + ")");
                return;
            }
        }
        if (!symlink && Files.isSymbolicLink(out)) throw new IOException("Unexpected symbolic link during metadata restore: " + out);
        UserPrincipalLookupService lookup = out.getFileSystem().getUserPrincipalLookupService();
        PosixFileAttributeView posix = Files.getFileAttributeView(out, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (!entry.owner().isBlank()) attempt(entry, "owner", warnings, () -> {
            FileOwnerAttributeView view = Files.getFileAttributeView(out, FileOwnerAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (view == null) throw new UnsupportedOperationException("owner view unavailable");
            if (!view.getOwner().getName().equals(entry.owner())) {
                if (symlink && posix == null) throw new UnsupportedOperationException("No safe symbolic-link owner setter on this platform");
                view.setOwner(lookup.lookupPrincipalByName(entry.owner()));
            }
        });
        if (!entry.group().isBlank()) attempt(entry, "group", warnings, () -> {
            if (posix == null) throw new UnsupportedOperationException("POSIX view unavailable");
            if (!posix.readAttributes().group().getName().equals(entry.group())) posix.setGroup(lookup.lookupPrincipalByGroupName(entry.group()));
        });
        if (!symlink && !fifo && !entry.permissions().isBlank()) attempt(entry, "permissions", warnings, () -> {
            if (posix == null) throw new UnsupportedOperationException("POSIX view unavailable");
            posix.setPermissions(PosixFilePermissions.fromString(entry.permissions()));
        });
        if (symlink && !entry.permissions().isBlank()) attempt(entry, "symbolic-link permissions", warnings,
                () -> symbolicLinkPermissions.restore(out, PosixFilePermissions.fromString(entry.permissions())));
        if (fifo) {
            // JDK 的 POSIX 权限/基本时间 setter 可能打开 FIFO 导致阻塞：权限已在 mkfifo 前给足，
            // 此处只恢复时间戳（FifoSupport 同样全程不打开数据流）。
            fifoSupport.restoreTimes(entry, out, warnings);
            return;
        }
        BasicFileAttributeView basic = Files.getFileAttributeView(out, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        attempt(entry, "modified/access time", warnings, () -> basic.setTimes(FileTime.fromMillis(entry.lastModifiedMillis()),
                entry.lastAccessMillis() == null ? null : FileTime.fromMillis(entry.lastAccessMillis()), null));
        if (entry.creationMillis() != null) attempt(entry, "creation time", warnings,
                () -> basic.setTimes(null, null, FileTime.fromMillis(entry.creationMillis())));
    }

    /** 单属性尽力设置：任何失败（IO/不支持/参数/安全）都转为路径级警告，绝不中断整体还原。 */
    private void attempt(BackupEntry entry, String attribute, Consumer<String> warnings, IoAction action) {
        try { action.run(); }
        catch (IOException | UnsupportedOperationException | IllegalArgumentException | SecurityException e) {
            warnings.accept(entry.relativePath() + ": could not restore " + attribute + " (" + e.getMessage() + ")");
        }
    }
    @FunctionalInterface private interface IoAction { void run() throws IOException; }

    /**
     * 路径安全核心：拒绝空/反斜杠/绝对路径/任何 "." ".." 组件；
     * root.resolve + normalize 后必须仍在 root 之内——规范化逃逸检测，
     * 让 "a/../../outside" 这类构造在写入前即被拒绝。
     */
    private Path safeResolve(Path root, String relative) throws IOException {
        if (relative.isBlank() || relative.indexOf('\\') >= 0) throw new IOException("Invalid archive path: " + relative);
        Path path;
        try { path = Path.of(relative); } catch (InvalidPathException e) { throw new IOException("Invalid archive path", e); }
        if (path.isAbsolute()) throw new IOException("Absolute archive path: " + relative);
        for (Path component : path) if (component.toString().equals("..") || component.toString().equals(".")) throw new IOException("Unsafe archive path: " + relative);
        Path resolved = root.resolve(path).normalize();
        if (resolved.equals(root) || !resolved.startsWith(root)) throw new IOException("Archive path escapes restore directory: " + relative);
        return resolved;
    }

    /** 逐级符号链接检查：从 root 走向 out 的每一级都不能是链接（防 link→/etc + link/passwd 两步组合攻击）。 */
    private void checkParents(Path root, Path out) throws IOException {
        Path current = root;
        for (Path component : root.relativize(out)) {
            if (current.equals(out)) break;
            if (Files.isSymbolicLink(current)) throw new IOException("Symbolic link in restore path: " + current);
            current = current.resolve(component);
        }
        // 最后一级组件按条目类型单独校验（文件/目录/链接各有各的拒覆盖规则）。
        if (Files.isSymbolicLink(out.getParent())) throw new IOException("Symbolic link in restore path: " + out.getParent());
    }

    /** 精确拷贝：按声明的 size 读取，不足即视为归档截断（EOFException）。 */
    private static void copyExactly(InputStream in, OutputStream out, long size) throws IOException {
        if (size < 0) throw new IOException("Invalid file size");
        byte[] buffer = new byte[64 * 1024];
        while (size > 0) { int read = in.read(buffer, 0, (int) Math.min(buffer.length, size)); if (read < 0) throw new EOFException("Truncated archive"); out.write(buffer, 0, read); size -= read; }
    }
    /** 替换式移动：优先 ATOMIC_MOVE，文件系统不支持时退化为普通 REPLACE_EXISTING 移动。 */
    private static void moveReplacing(Path from, Path to) throws IOException {
        try { Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException e) { Files.move(from, to, StandardCopyOption.REPLACE_EXISTING); }
    }
    /** 有界读清单：最多 MAX_MANIFEST+1 字节，超界即拒绝（防内存耗尽）。 */
    private static byte[] readBounded(InputStream in) throws IOException {
        byte[] bytes = in.readNBytes(MAX_MANIFEST + 1); if (bytes.length > MAX_MANIFEST) throw new IOException("Manifest too large"); return bytes;
    }
    /** 长度前缀字符串写/读：读侧长度上限 1MB，防伪造超长字符串。 */
    private static void writeString(DataOutputStream out, String value) throws IOException { byte[] bytes = value.getBytes(StandardCharsets.UTF_8); out.writeInt(bytes.length); out.write(bytes); }
    /** 长度前缀字符串读取：长度上限 1MB，防伪造。 */
    private static String readString(DataInputStream in) throws IOException {
        int size = in.readInt(); if (size < 0 || size > 1024 * 1024) throw new IOException("Invalid string size");
        byte[] bytes = in.readNBytes(size); if (bytes.length != size) throw new EOFException("Truncated string"); return new String(bytes, StandardCharsets.UTF_8);
    }
}
