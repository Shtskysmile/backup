package com.backup.core;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import java.util.function.Consumer;

public final class FileScanner {
    private static final int TYPE_MASK = 0170000;
    private static final boolean IS_MAC = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");

    public List<BackupEntry> scan(Path root) throws IOException {
        return scan(root, BackupFilter.all(), ignored -> {});
    }

    public List<BackupEntry> scan(Path root, BackupFilter filter, Consumer<String> warnings) throws IOException {
        Path normalizedRoot = root.toRealPath();
        if (!Files.isDirectory(normalizedRoot)) throw new IOException("Source is not a directory: " + root);
        Map<String, RawEntry> raw = new TreeMap<>();
        Files.walkFileTree(normalizedRoot, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (!dir.equals(normalizedRoot)) {
                    String relative = relative(normalizedRoot, dir);
                    if (filter.excludesDirectory(relative)) return FileVisitResult.SKIP_SUBTREE;
                    raw.put(relative, capture(dir, relative, attrs));
                }
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String relative = relative(normalizedRoot, file);
                RawEntry rawEntry = capture(file, relative, attrs);
                if (rawEntry.type() == null) {
                    warnings.accept("Skipped unsupported special file: " + relative);
                } else if (filter.matches(relative, rawEntry.size(), attrs.lastModifiedTime().toMillis(), rawEntry.owner())) {
                    raw.put(relative, rawEntry);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        Set<String> needed = new HashSet<>();
        for (RawEntry entry : raw.values()) {
            if (entry.type() != EntryType.DIRECTORY) {
                Path parent = Path.of(entry.relative()).getParent();
                while (parent != null) { needed.add(parent.toString().replace('\\', '/')); parent = parent.getParent(); }
            }
        }
        Map<Object, BackupEntry> byIdentity = new HashMap<>();
        List<BackupEntry> result = new ArrayList<>();
        for (RawEntry rawEntry : raw.values()) {
            BasicFileAttributes attrs = rawEntry.attrs();
            EntryType type = rawEntry.type();
            if (attrs.isDirectory() && !filter.isUnrestricted() && !needed.contains(rawEntry.relative())) continue;
            String target = "", hash = "";
            if (type == EntryType.SYMBOLIC_LINK) target = Files.readSymbolicLink(rawEntry.path()).toString();
            if (type == EntryType.FILE) {
                BackupEntry first = attrs.fileKey() == null ? null : byIdentity.get(attrs.fileKey());
                if (first != null) { type = EntryType.HARD_LINK; target = first.relativePath(); hash = first.sha256(); }
                else {
                    hash = HashUtils.sha256(rawEntry.path());
                    if (attrs.fileKey() == null) warnings.accept("Hardlink identity unavailable; stored as ordinary file: " + rawEntry.relative());
                }
            }
            if (type == EntryType.CHARACTER_DEVICE || type == EntryType.BLOCK_DEVICE) target = rawEntry.deviceTarget();
            BackupEntry entry = new BackupEntry(rawEntry.relative(), attrs.isDirectory(), rawEntry.size(),
                    attrs.lastModifiedTime().toMillis(), hash, rawEntry.permissions(), rawEntry.owner(), rawEntry.group(),
                    type, target, attrs.lastAccessTime().toMillis(), attrs.creationTime().toMillis());
            if (type == EntryType.FILE && attrs.fileKey() != null) byIdentity.put(attrs.fileKey(), entry);
            result.add(entry);
        }
        return result;
    }

    private static String relative(Path root, Path path) { return root.relativize(path).toString().replace('\\', '/'); }

    private RawEntry capture(Path path, String relative, BasicFileAttributes attrs) throws IOException {
        String permissions = "", owner = "", group = "";
        try {
            PosixFileAttributes posix = Files.readAttributes(path, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            permissions = PosixFilePermissions.toString(posix.permissions()); owner = posix.owner().getName(); group = posix.group().getName();
        } catch (UnsupportedOperationException ignored) {
            FileOwnerAttributeView view = Files.getFileAttributeView(path, FileOwnerAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (view != null) owner = view.getOwner().getName();
        }
        EntryType type = attrs.isDirectory() ? EntryType.DIRECTORY
                : attrs.isSymbolicLink() ? EntryType.SYMBOLIC_LINK
                : attrs.isRegularFile() ? EntryType.FILE : classifySpecial(path, attrs);
        String deviceTarget = type == EntryType.CHARACTER_DEVICE || type == EntryType.BLOCK_DEVICE
                ? deviceNumber(path) : "";
        long size = attrs.isDirectory() || (type != null && type != EntryType.FILE && type != EntryType.SYMBOLIC_LINK) ? 0 : attrs.size();
        return new RawEntry(path, relative, attrs, size, permissions, owner, group, type, deviceTarget);
    }

    /**
     * Recognizes pipes, devices and sockets through POSIX type bits when the runtime exposes
     * them ("unix:mode" on OpenJDK Unix platforms). Returns null for anything unclassifiable
     * on the current platform.
     */
    private static EntryType classifySpecial(Path path, BasicFileAttributes attrs) {
        if (attrs.isRegularFile() || attrs.isSymbolicLink() || attrs.isDirectory()) return null;
        Integer mode = unixMode(path);
        if (mode == null) return null;
        return switch (mode & TYPE_MASK) {
            case 0010000 -> EntryType.FIFO;
            case 0020000 -> EntryType.CHARACTER_DEVICE;
            case 0060000 -> EntryType.BLOCK_DEVICE;
            case 0140000 -> EntryType.SOCKET;
            default -> null;
        };
    }

    private static Integer unixMode(Path path) {
        try {
            Object value = Files.getAttribute(path, "unix:mode", LinkOption.NOFOLLOW_LINKS);
            return value instanceof Integer integer ? integer : null;
        } catch (IOException | UnsupportedOperationException | IllegalArgumentException e) {
            return null;
        }
    }

    private static String deviceNumber(Path path) {
        try {
            Object value = Files.getAttribute(path, "unix:rdev", LinkOption.NOFOLLOW_LINKS);
            if (!(value instanceof Long rdev)) return "";
            long major = IS_MAC ? (rdev >> 24) & 0xff : ((rdev >> 8) & 0xfff) | ((rdev >> 32) & ~0xfffL);
            long minor = IS_MAC ? rdev & 0xffffff : (rdev & 0xff) | ((rdev >> 12) & ~0xffL);
            if (major < 0 || minor < 0 || major > 65535 || minor > 65535) return "";
            return major + ":" + minor;
        } catch (IOException | UnsupportedOperationException | IllegalArgumentException e) {
            return "";
        }
    }

    private record RawEntry(Path path, String relative, BasicFileAttributes attrs, long size,
                            String permissions, String owner, String group, EntryType type, String deviceTarget) {}
}
