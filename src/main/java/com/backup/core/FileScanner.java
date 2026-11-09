package com.backup.core;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import java.util.function.Consumer;

/**
 * 把源目录树读成 {@link List<BackupEntry>} 的唯一入口。
 *
 * <p>算法分两遍：第一遍 walkFileTree（天然不跟随符号链接）把每个条目连同属性、属主、
 * 分类结果收入 TreeMap（相对路径字典序，保证输出确定性）；第二遍据此构建 BackupEntry，
 * 同时完成父目录保留计算与硬链接身份归并。</p>
 *
 * <p>分类规则：目录/软链接/常规文件由属性直接判定；其余条目经 "unix:mode" 类型位
 * （掩码 0170000）识别为 FIFO/字符设备/块设备/套接字——该视图仅 OpenJDK Unix 平台提供，
 * Windows 上抛 UnsupportedOperationException，返回 null 后跳过并警告（能力探测 + 降级）。</p>
 *
 * <p>属主采集同样先探测 POSIX 视图，不支持时退回 FileOwnerAttributeView（Windows）；
 * 属主既写入清单，也参与 {@link BackupFilter} 的属主筛选。</p>
 */
public final class FileScanner {
    /** st_mode 的高 4 位类型掩码（八进制 0170000）。 */
    private static final int TYPE_MASK = 0170000;
    /** mac 的 st_rdev 位布局与 Linux 不同（BSD 高 8 位 major / 低 24 位 minor），预判一次。 */
    private static final boolean IS_MAC = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");

    /** 扫描整棵树（无筛选）。测试与工具用途；产品路径请使用带筛选的重载。 */
    public List<BackupEntry> scan(Path root) throws IOException {
        return scan(root, BackupFilter.all(), ignored -> {});
    }

    /**
     * 带筛选的完整扫描。warnings 收集两类非致命事件：跳过的无法分类特殊文件、
     * Windows 上硬链接身份不可用的降级说明。筛选在属主采集之后进行——属主筛选需要它。
     */
    public List<BackupEntry> scan(Path root, BackupFilter filter, Consumer<String> warnings) throws IOException {
        Path normalizedRoot = root.toRealPath();
        if (!Files.isDirectory(normalizedRoot))
            throw new IOException("Source is not a directory: " + root);
        Map<String, RawEntry> raw = new TreeMap<>();
        Files.walkFileTree(normalizedRoot, new SimpleFileVisitor<>() {
            // 目录前置访问：根目录本身不入清单；其余目录先过排除规则（整树剪枝），再采集。
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (!dir.equals(normalizedRoot)) {
                    String relative = relative(normalizedRoot, dir);
                    if (filter.excludesDirectory(relative))
                        return FileVisitResult.SKIP_SUBTREE;
                    raw.put(relative, capture(dir, relative, attrs));
                }
                return FileVisitResult.CONTINUE;
            }
            // 文件/特殊文件访问：无法分类的跳过并警告；可分类的先采集再过筛选（属主筛选需要属主）。
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String relative = relative(normalizedRoot, file);
                RawEntry rawEntry = capture(file, relative, attrs);
                if (rawEntry.type() == null) {
                    warnings.accept("Skipped unsupported special file: " + relative);
                } else if (filter.matches(
                                   relative, rawEntry.size(), attrs.lastModifiedTime().toMillis(), rawEntry.owner())) {
                    raw.put(relative, rawEntry);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        // 第二遍构建：先计算"非目录条目所需父目录"集合（needed），
        // 再按序生成 BackupEntry；受限筛选下空目录（无命中子项）被剪掉。
        Set<String> needed = new HashSet<>();
        for (RawEntry entry : raw.values()) {
            if (entry.type() != EntryType.DIRECTORY) {
                Path parent = Path.of(entry.relative()).getParent();
                while (parent != null) {
                    needed.add(parent.toString().replace('\\', '/'));
                    parent = parent.getParent();
                }
            }
        }
        Map<Object, BackupEntry> byIdentity = new HashMap<>();
        // fileKey → 首个 BackupEntry：硬链接归并表（Windows fileKey=null 时天然跳过）。
        List<BackupEntry> result = new ArrayList<>();
        for (RawEntry rawEntry : raw.values()) {
            BasicFileAttributes attrs = rawEntry.attrs();
            EntryType type = rawEntry.type();
            if (attrs.isDirectory() && !filter.isUnrestricted() && !needed.contains(rawEntry.relative()))
                continue;
            // 受限筛选下的空目录剪枝：保留命中条目的祖先链，丢弃无子项目录。
            String target = "", hash = "";
            if (type == EntryType.SYMBOLIC_LINK)
                target = Files.readSymbolicLink(rawEntry.path()).toString();
            if (type == EntryType.FILE) {
                // 硬链接归并：fileKey 是文件系统级身份。第二次出现同一身份时不再读盘算哈希，
                // 只记录"指向第一个名字"——同一内容零重复 I/O，且还原时可重建链接。
                // Windows 的 fileKey() 返回 null → 每个名字各自成普通文件并警告（降级）。
                BackupEntry first = attrs.fileKey() == null ? null : byIdentity.get(attrs.fileKey());
                if (first != null) {
                    type = EntryType.HARD_LINK;
                    target = first.relativePath();
                    hash = first.sha256();
                } else {
                    hash = HashUtils.sha256(rawEntry.path());
                    if (attrs.fileKey() == null)
                        warnings.accept("Hardlink identity unavailable; stored as ordinary file: " +
                                        rawEntry.relative());
                }
            }
            // 设备条目把"主:次"设备号放进 linkTarget 字段（清单协议复用，见 validateManifest 的白名单校验）。
            if (type == EntryType.CHARACTER_DEVICE || type == EntryType.BLOCK_DEVICE)
                target = rawEntry.deviceTarget();
            BackupEntry entry = new BackupEntry(rawEntry.relative(),
                                                attrs.isDirectory(),
                                                rawEntry.size(),
                                                attrs.lastModifiedTime().toMillis(),
                                                hash,
                                                rawEntry.permissions(),
                                                rawEntry.owner(),
                                                rawEntry.group(),
                                                type,
                                                target,
                                                attrs.lastAccessTime().toMillis(),
                                                attrs.creationTime().toMillis());
            if (type == EntryType.FILE && attrs.fileKey() != null)
                byIdentity.put(attrs.fileKey(), entry);
            result.add(entry);
        }
        return result;
    }

    /** 相对路径统一用 / 分隔（清单协议跨平台稳定的基石）。 */
    private static String relative(Path root, Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }

    /**
     * 单条目采集：属主/组/权限（POSIX 优先，Windows 退化为属主视图）、
     * 类型判定、设备号与记录尺寸（目录/特殊文件恒为 0，常规文件与软链接保留真实值）。
     */
    private RawEntry capture(Path path, String relative, BasicFileAttributes attrs) throws IOException {
        String permissions = "", owner = "", group = "";
        try {
            PosixFileAttributes posix =
                    Files.readAttributes(path, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            permissions = PosixFilePermissions.toString(posix.permissions());
            owner = posix.owner().getName();
            group = posix.group().getName();
        } catch (UnsupportedOperationException ignored) {
            FileOwnerAttributeView view =
                    Files.getFileAttributeView(path, FileOwnerAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (view != null)
                owner = view.getOwner().getName();
        }
        EntryType type = attrs.isDirectory()      ? EntryType.DIRECTORY
                         : attrs.isSymbolicLink() ? EntryType.SYMBOLIC_LINK
                         : attrs.isRegularFile()  ? EntryType.FILE
                                                  : classifySpecial(path, attrs);
        String deviceTarget =
                type == EntryType.CHARACTER_DEVICE || type == EntryType.BLOCK_DEVICE ? deviceNumber(path) : "";
        long size = attrs.isDirectory() || (type != null && type != EntryType.FILE && type != EntryType.SYMBOLIC_LINK)
                            ? 0
                            : attrs.size();
        return new RawEntry(path, relative, attrs, size, permissions, owner, group, type, deviceTarget);
    }

    /**
     * 特殊文件分类：运行时暴露 POSIX 类型位时（OpenJDK Unix 平台的 "unix:mode" 视图），
     * 据掩码高位识别管道、设备与套接字；当前平台无法分类的条目返回 null，由调用方跳过并警告。
     */
    private static EntryType classifySpecial(Path path, BasicFileAttributes attrs) {
        if (attrs.isRegularFile() || attrs.isSymbolicLink() || attrs.isDirectory())
            return null;
        Integer mode = unixMode(path);
        if (mode == null)
            return null;
        return switch (mode & TYPE_MASK) {
            case 0010000 -> EntryType.FIFO;
            case 0020000 -> EntryType.CHARACTER_DEVICE;
            case 0060000 -> EntryType.BLOCK_DEVICE;
            case 0140000 -> EntryType.SOCKET;
            default -> null;
        };
    }

    /** 读 unix:mode 类型位；任何失败（Windows/无权限/IO）都安静返回 null，由调用方按"无法分类"处理。 */
    private static Integer unixMode(Path path) {
        try {
            Object value = Files.getAttribute(path, "unix:mode", LinkOption.NOFOLLOW_LINKS);
            return value instanceof Integer integer ? integer : null;
        } catch (IOException | UnsupportedOperationException | IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 从 unix:rdev（原始 st_rdev）解码设备号为 "major:minor"。
     * Linux 用 glibc 大编码宏（major 高位拼接），macOS/BSD 用高 8 位/低 24 位；
     * 解码结果做范围校验（0..65535），跨平台还原错误设备号会由 mknod 失败并降级为警告。
     */
    private static String deviceNumber(Path path) {
        try {
            Object value = Files.getAttribute(path, "unix:rdev", LinkOption.NOFOLLOW_LINKS);
            if (!(value instanceof Long rdev))
                return "";
            long major = IS_MAC ? (rdev >> 24) & 0xff : ((rdev >> 8) & 0xfff) | ((rdev >> 32) & ~0xfffL);
            long minor = IS_MAC ? rdev & 0xffffff : (rdev & 0xff) | ((rdev >> 12) & ~0xffL);
            if (major < 0 || minor < 0 || major > 65535 || minor > 65535)
                return "";
            return major + ":" + minor;
        } catch (IOException | UnsupportedOperationException | IllegalArgumentException e) {
            return "";
        }
    }

    /** 扫描期中间态：原始路径 + 规范化后的分类结果 + 记录尺寸，第二遍构建直接消费。 */
    private record RawEntry(Path path,
                            String relative,
                            BasicFileAttributes attrs,
                            long size,
                            String permissions,
                            String owner,
                            String group,
                            EntryType type,
                            String deviceTarget) {}
}
