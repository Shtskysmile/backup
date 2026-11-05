package com.backup.core;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import java.util.function.Consumer;

public final class FileScanner {
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
                boolean fifo = !attrs.isRegularFile() && !attrs.isSymbolicLink() && isFifo(file);
                if (!attrs.isRegularFile() && !attrs.isSymbolicLink() && !fifo) {
                    warnings.accept("Skipped unsupported special file: " + relative);
                } else {
                    RawEntry entry = capture(file, relative, attrs);
                    if (filter.matches(relative, fifo ? 0 : attrs.size(), attrs.lastModifiedTime().toMillis(), entry.owner())) {
                        raw.put(relative, entry);
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });
        Set<String> needed = new HashSet<>();
        for (RawEntry entry : raw.values()) {
            if (!entry.attrs().isDirectory()) {
                Path parent = Path.of(entry.relative()).getParent();
                while (parent != null) { needed.add(parent.toString().replace('\\', '/')); parent = parent.getParent(); }
            }
        }
        Map<Object, BackupEntry> byIdentity = new HashMap<>();
        List<BackupEntry> result = new ArrayList<>();
        for (RawEntry rawEntry : raw.values()) {
            BasicFileAttributes attrs = rawEntry.attrs();
            if (attrs.isDirectory() && !filter.isUnrestricted() && !needed.contains(rawEntry.relative())) continue;
            EntryType type = attrs.isDirectory() ? EntryType.DIRECTORY : attrs.isSymbolicLink() ? EntryType.SYMBOLIC_LINK : attrs.isRegularFile() ? EntryType.FILE : EntryType.FIFO;
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
            BackupEntry entry = new BackupEntry(rawEntry.relative(), attrs.isDirectory(), (attrs.isDirectory() || type == EntryType.FIFO) ? 0 : attrs.size(),
                    attrs.lastModifiedTime().toMillis(), hash, rawEntry.permissions(), rawEntry.owner(), rawEntry.group(),
                    type, target, attrs.lastAccessTime().toMillis(), attrs.creationTime().toMillis());
            if (type == EntryType.FILE && attrs.fileKey() != null) byIdentity.put(attrs.fileKey(), entry);
            result.add(entry);
        }
        return result;
    }

    private static boolean isFifo(Path path) {
        try {
            Object mode = Files.getAttribute(path, "unix:mode", LinkOption.NOFOLLOW_LINKS);
            return mode instanceof Integer bits && (bits & 0170000) == 0010000;
        } catch (IOException | UnsupportedOperationException | IllegalArgumentException e) { return false; }
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
        return new RawEntry(path, relative, attrs, permissions, owner, group);
    }
    private record RawEntry(Path path, String relative, BasicFileAttributes attrs, String permissions, String owner, String group) {}
}
