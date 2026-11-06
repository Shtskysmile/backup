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

public final class ArchiveService {
    private static final String ZIP_DATA_PREFIX = "data/";
    private static final String GZIP_MAGIC = "BACKPACK-GZIP-2";
    private final FifoSupport fifoSupport;
    private final SymbolicLinkPermissions symbolicLinkPermissions = new SymbolicLinkPermissions();
    public ArchiveService() { this(new FifoSupport()); }
    ArchiveService(FifoSupport fifoSupport) { this.fifoSupport = Objects.requireNonNull(fifoSupport); }

    private static final int MAX_MANIFEST = 64 * 1024 * 1024;

    public void writeZipArchive(Path sourceRoot, Path output, BackupManifest manifest, Iterable<BackupEntry> archivedEntries) throws IOException {
        Files.createDirectories(output.getParent());
        try (ZipOutputStream out = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(output)))) {
            out.putNextEntry(new ZipEntry(BackupManifest.ARCHIVE_ENTRY_NAME)); out.write(manifest.toBytes()); out.closeEntry();
            for (BackupEntry entry : archivedEntries) {
                ZipEntry zip = new ZipEntry(ZIP_DATA_PREFIX + entry.relativePath() + (entry.directory() ? "/" : ""));
                zip.setLastModifiedTime(FileTime.fromMillis(entry.lastModifiedMillis()));
                out.putNextEntry(zip);
                if (entry.type() == EntryType.FILE) copySource(sourceRoot, entry, out);
                out.closeEntry();
            }
        }
    }

    public void writeGzipArchive(Path sourceRoot, Path output, BackupManifest manifest, Iterable<BackupEntry> archivedEntries) throws IOException {
        Files.createDirectories(output.getParent());
        List<BackupEntry> entries = new ArrayList<>(); archivedEntries.forEach(entries::add);
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(output))))) {
            writeString(out, GZIP_MAGIC); byte[] bytes = manifest.toBytes(); out.writeInt(bytes.length); out.write(bytes); out.writeInt(entries.size());
            for (BackupEntry entry : entries) {
                writeString(out, entry.relativePath()); out.writeBoolean(entry.directory());
                out.writeLong(entry.type() == EntryType.FILE ? entry.size() : 0); out.writeLong(entry.lastModifiedMillis());
                if (entry.type() == EntryType.FILE) copySource(sourceRoot, entry, out);
            }
        }
    }

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

    public BackupManifest restore(Path archive, Path destination) throws IOException { return restoreDetailed(archive, destination).manifest(); }

    public RestoreResult restoreDetailed(Path archive, Path destination) throws IOException {
        Path root = destination.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(root)) throw new IOException("Restore destination is a symbolic link: " + root);
        Files.createDirectories(root); root = root.toRealPath();
        byte[] header;
        try (InputStream in = Files.newInputStream(archive)) { header = in.readNBytes(4); }
        if (header.length >= 2 && header[0] == 'P' && header[1] == 'K') return restoreZip(archive, root);
        if (header.length >= 2 && (header[0] & 255) == 31 && (header[1] & 255) == 139) return restoreGzip(archive, root);
        throw new IOException("Unsupported archive type: " + archive);
    }

    private RestoreResult restoreZip(Path archive, Path root) throws IOException {
        BackupManifest manifest;
        List<BackupEntry> restored = new ArrayList<>(); Set<String> seen = new HashSet<>();
        List<String> warnings = new ArrayList<>(); Set<String> skipped = new HashSet<>();
        try (ZipInputStream in = new ZipInputStream(new BufferedInputStream(Files.newInputStream(archive)))) {
            ZipEntry first = in.getNextEntry();
            if (first == null || !BackupManifest.ARCHIVE_ENTRY_NAME.equals(first.getName())) throw new IOException("Archive must start with a Backup manifest");
            manifest = BackupManifest.fromString(new String(readBounded(in), StandardCharsets.UTF_8));
            Map<String, BackupEntry> byPath = validateManifest(root, manifest);
            in.closeEntry(); ZipEntry zip;
            while ((zip = in.getNextEntry()) != null) {
                if (!zip.getName().startsWith(ZIP_DATA_PREFIX)) throw new IOException("Unexpected archive entry: " + zip.getName());
                String path = zip.getName().substring(ZIP_DATA_PREFIX.length());
                if (path.endsWith("/")) path = path.substring(0, path.length() - 1);
                BackupEntry entry = requireEntry(byPath, seen, path);
                if (zip.isDirectory() != entry.directory()) throw new IOException("Entry type mismatch: " + path);
                restorePayload(root, entry, in, entry.type() == EntryType.FILE ? entry.size() : 0, warnings, skipped);
                if (in.read() != -1) throw new IOException("Unexpected extra entry data: " + path);
                restored.add(entry); in.closeEntry();
            }
        }
        return finish(root, manifest, restored, warnings, skipped);
    }

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

    private BackupEntry requireEntry(Map<String, BackupEntry> entries, Set<String> seen, String path) throws IOException {
        BackupEntry entry = entries.get(path);
        if (entry == null || !seen.add(path)) throw new IOException("Unknown or duplicate archive path: " + path);
        return entry;
    }

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
                moveReplacing(staged, out);
            } finally { Files.deleteIfExists(staged); }
        } else if (entry.type() == EntryType.FIFO && !restoreFifo(root, entry, warnings::add)) {
            skipped.add(entry.relativePath());
        } else if ((entry.type() == EntryType.CHARACTER_DEVICE || entry.type() == EntryType.BLOCK_DEVICE
                || entry.type() == EntryType.SOCKET) && !restoreDevice(root, entry, warnings::add)) {
            skipped.add(entry.relativePath());
        }
    }

    /** Devices are recreated with mknod when the platform and privileges allow; sockets are recorded only. */
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

    private RestoreResult finish(Path root, BackupManifest manifest, List<BackupEntry> entries, List<String> warnings, Set<String> skipped) throws IOException {
        if (!manifest.incremental() && entries.size() != manifest.entries().size()) {
            throw new IOException("Full archive is missing file or directory payload entries");
        }
        for (EntryType type : List.of(EntryType.HARD_LINK, EntryType.SYMBOLIC_LINK)) {
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
                    } else Files.createSymbolicLink(staged, Path.of(entry.linkTarget()));
                    moveReplacing(staged, out);
                } catch (UnsupportedOperationException | SecurityException | InvalidPathException e) {
                    throw new IOException("Cannot restore link: " + entry.relativePath(), e);
                } finally { Files.deleteIfExists(staged); }
            }
        }
        List<BackupEntry> metadata = new ArrayList<>(entries.stream().filter(entry -> !skipped.contains(entry.relativePath())).toList());
        if (manifest.incremental()) {
            Set<String> restoredPaths = new HashSet<>();
            entries.forEach(entry -> restoredPaths.add(entry.relativePath()));
            for (BackupEntry entry : manifest.entries()) if (!restoredPaths.contains(entry.relativePath())) {
                if (baselineMatches(root, entry)) metadata.add(entry);
                else warnings.add(entry.relativePath() + ": incremental baseline missing or different; restore the full backup and preceding increments first");
            }
        }
        metadata.sort(Comparator.comparingInt((BackupEntry entry) -> Path.of(entry.relativePath()).getNameCount()).reversed());
        for (BackupEntry entry : metadata) applyMetadata(root, entry, warnings::add);
        return new RestoreResult(manifest, warnings);
    }

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

    private boolean matchesFile(Path path, BackupEntry entry) throws IOException {
        return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files.size(path) == entry.size()
                && !entry.sha256().isEmpty() && HashUtils.sha256(path).equals(entry.sha256());
    }

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
            // JDK POSIX permission and basic time setters may open a FIFO and block.
            // Permissions were supplied to mkfifo before publishing this entry.
            fifoSupport.restoreTimes(entry, out, warnings);
            return;
        }
        BasicFileAttributeView basic = Files.getFileAttributeView(out, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        attempt(entry, "modified/access time", warnings, () -> basic.setTimes(FileTime.fromMillis(entry.lastModifiedMillis()),
                entry.lastAccessMillis() == null ? null : FileTime.fromMillis(entry.lastAccessMillis()), null));
        if (entry.creationMillis() != null) attempt(entry, "creation time", warnings,
                () -> basic.setTimes(null, null, FileTime.fromMillis(entry.creationMillis())));
    }

    private void attempt(BackupEntry entry, String attribute, Consumer<String> warnings, IoAction action) {
        try { action.run(); }
        catch (IOException | UnsupportedOperationException | IllegalArgumentException | SecurityException e) {
            warnings.accept(entry.relativePath() + ": could not restore " + attribute + " (" + e.getMessage() + ")");
        }
    }
    @FunctionalInterface private interface IoAction { void run() throws IOException; }

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

    private void checkParents(Path root, Path out) throws IOException {
        Path current = root;
        for (Path component : root.relativize(out)) {
            if (current.equals(out)) break;
            if (Files.isSymbolicLink(current)) throw new IOException("Symbolic link in restore path: " + current);
            current = current.resolve(component);
        }
        // The final component is checked separately according to the entry's type.
        if (Files.isSymbolicLink(out.getParent())) throw new IOException("Symbolic link in restore path: " + out.getParent());
    }

    private static void copyExactly(InputStream in, OutputStream out, long size) throws IOException {
        if (size < 0) throw new IOException("Invalid file size");
        byte[] buffer = new byte[64 * 1024];
        while (size > 0) { int read = in.read(buffer, 0, (int) Math.min(buffer.length, size)); if (read < 0) throw new EOFException("Truncated archive"); out.write(buffer, 0, read); size -= read; }
    }
    private static void moveReplacing(Path from, Path to) throws IOException {
        try { Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException e) { Files.move(from, to, StandardCopyOption.REPLACE_EXISTING); }
    }
    private static byte[] readBounded(InputStream in) throws IOException {
        byte[] bytes = in.readNBytes(MAX_MANIFEST + 1); if (bytes.length > MAX_MANIFEST) throw new IOException("Manifest too large"); return bytes;
    }
    private static void writeString(DataOutputStream out, String value) throws IOException { byte[] bytes = value.getBytes(StandardCharsets.UTF_8); out.writeInt(bytes.length); out.write(bytes); }
    private static String readString(DataInputStream in) throws IOException {
        int size = in.readInt(); if (size < 0 || size > 1024 * 1024) throw new IOException("Invalid string size");
        byte[] bytes = in.readNBytes(size); if (bytes.length != size) throw new EOFException("Truncated string"); return new String(bytes, StandardCharsets.UTF_8);
    }
}
