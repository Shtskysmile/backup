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

/** Single-instance operations are serialized; no server or persistent password state is required. */
public final class BackupService {
    private static final String LATEST_MANIFEST_NAME = ".backpack-manifest.properties";
    private static final DateTimeFormatter ARCHIVE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private final FileScanner fileScanner;
    private final ArchiveService archiveService;
    private final EncryptionService encryptionService;
    private final Map<String, Baseline> baselines = new HashMap<>();

    public BackupService() { this(new FileScanner(), new ArchiveService(), new EncryptionService()); }
    public BackupService(FileScanner scanner, ArchiveService archive, EncryptionService encryption) {
        fileScanner = scanner; archiveService = archive; encryptionService = encryption;
    }

    public synchronized BackupResult backup(BackupOptions options) throws IOException {
        Instant started = Instant.now();
        Path source = options.sourceDirectory().toRealPath();
        Path destination = options.destinationDirectory().toAbsolutePath().normalize();
        if (destination.equals(source) || destination.startsWith(source)) throw new IOException("Destination directory must not be inside the source directory.");
        Files.createDirectories(destination); destination = destination.toRealPath();
        if (destination.equals(source) || destination.startsWith(source)) throw new IOException("Destination directory must not be inside the source directory.");
        String taskId = taskIdentity(source, options);
        String key = destination + "/" + taskId;
        Baseline baseline = baselines.get(key);
        boolean incremental = options.incremental() && baseline != null && baseline.compatible(options)
                && baseline.manifest().snapshot().sequence() < 10 && baseline.chainAvailable();
        List<String> warnings = new ArrayList<>();
        List<BackupEntry> scanned = fileScanner.scan(source, options.filter(), warnings::add);
        Map<String, BackupEntry> previous = incremental ? baseline.manifest().entriesByPath() : Map.of();
        List<BackupEntry> archived = scanned.stream().filter(entry -> !incremental || entry.directory() || !entry.sameContentAs(previous.get(entry.relativePath()))).toList();
        String snapshotId = UUID.randomUUID().toString();
        Path archive = uniqueArchivePath(destination, archiveFileName(options, incremental, snapshotId));
        Path archiveManifest = destination.resolve(archive.getFileName() + ".manifest.properties");
        Path index = destination.resolve(archive.getFileName() + ".snapshot.properties");
        Instant created = Instant.now();
        BackupCatalog.Snapshot parent = incremental ? baseline.manifest().snapshot() : null;
        BackupCatalog.Snapshot snapshot = new BackupCatalog.Snapshot(taskId, snapshotId,
                incremental ? parent.groupId() : snapshotId, incremental ? parent.snapshotId() : "",
                incremental ? parent.sequence() + 1 : 1, archive.getFileName().toString(), created);
        BackupManifest manifest = new BackupManifest(source.toString(), created, options.compressionType(), incremental,
                options.encrypted(), scanned).withSnapshot(snapshot);
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
            manifest.store(destination.resolve(LATEST_MANIFEST_NAME));
            published = true;
        } finally {
            Files.deleteIfExists(staged);
            if (!clear.equals(staged)) Files.deleteIfExists(clear);
            if (!published && archiveMoved) {
                Files.deleteIfExists(index); Files.deleteIfExists(archiveManifest); Files.deleteIfExists(archive);
            }
        }
        List<Path> chain = new ArrayList<>(); if (incremental) chain.addAll(baseline.chain()); chain.add(archive);
        Baseline replacement = new Baseline(manifest, options.compressionType(), options.encrypted(),
                options.encrypted() ? options.passphrase() : new char[0], options.keepGroups() > 0, List.copyOf(chain));
        Baseline old = baselines.put(key, replacement); if (old != null) Arrays.fill(old.passphrase(), '\0');
        try { BackupCatalog.prune(destination, taskId, options.keepGroups(), warnings::add); }
        catch (IOException | RuntimeException e) { warnings.add("Backup completed, but retention failed: " + e.getMessage()); }
        return new BackupResult(archive, archiveManifest, scanned.size(), archived.size(), bytes,
                Duration.between(started, Instant.now()), options.encrypted(), warnings);
    }

    public synchronized BackupManifest restore(Path archive, Path destination, char[] passphrase) throws IOException {
        return restoreDetailed(archive, destination, passphrase).manifest();
    }
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

    /** Stable task identity shared by local and network backup flows. */
    public static String taskIdentity(Path source, BackupOptions options) {
        try {
            String name = options.backupName(), filter = options.filter().identity(), path = source.toString();
            String identity = path.length() + ":" + path + name.length() + ":" + name + filter.length() + ":" + filter;
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private String archiveFileName(BackupOptions options, boolean incremental, String snapshotId) {
        String safe = options.backupName().replaceAll("[^A-Za-z0-9._-]+", "-");
        if (safe.isBlank()) safe = "backup";
        return safe + "-" + (incremental ? "incremental" : "full") + "-" + LocalDateTime.now().format(ARCHIVE_TIMESTAMP) + "-" + snapshotId
                + options.compressionType().extension() + (options.encrypted() ? ".enc" : "");
    }
    private Path uniqueArchivePath(Path destination, String filename) {
        Path candidate = destination.resolve(filename); int suffix = 1;
        while (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)
                || Files.exists(candidate.resolveSibling(candidate.getFileName() + ".manifest.properties"), LinkOption.NOFOLLOW_LINKS)
                || Files.exists(candidate.resolveSibling(candidate.getFileName() + ".snapshot.properties"), LinkOption.NOFOLLOW_LINKS)) {
            candidate = destination.resolve(filename.replaceFirst("(\\.[^.]+(?:\\.enc)?)$", "-" + suffix++ + "$1"));
        }
        return candidate;
    }
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
