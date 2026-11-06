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
public final class NetworkBackupService {
    private static final DateTimeFormatter ARCHIVE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final int MAX_SEQUENCE = 10;
    private final FileScanner fileScanner = new FileScanner();
    private final ArchiveService archiveService = new ArchiveService();
    private final EncryptionService encryptionService = new EncryptionService();
    private final Map<String, char[]> passphraseMemory = new HashMap<>();

    public NetworkBackupResult backup(NetworkBackupClient connection, BackupOptions options, int keepGroups)
            throws IOException, InterruptedException {
        Path source = options.sourceDirectory().toRealPath();
        String taskId = BackupService.taskIdentity(source, options);
        List<NetworkBackupClient.RemoteSnapshot> remote = connection.listSnapshots().stream()
                .filter(snapshot -> snapshot.taskId().equals(taskId)).toList();
        NetworkBackupClient.RemoteSnapshot latest = remote.stream()
                .max(Comparator.comparingInt(NetworkBackupClient.RemoteSnapshot::sequence)
                        .thenComparing(NetworkBackupClient.RemoteSnapshot::createdAt))
                .orElse(null);
        String passphraseKey = connection.username() + "/" + taskId;
        char[] remembered = passphraseMemory.get(passphraseKey);
        boolean incremental = options.incremental() && latest != null && latest.sequence() < MAX_SEQUENCE;
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

    private String archiveFileName(BackupOptions options, boolean incremental, String snapshotId) {
        String safe = options.backupName().replaceAll("[^A-Za-z0-9._-]+", "-");
        if (safe.isBlank()) safe = "backup";
        return safe + "-" + (incremental ? "incremental" : "full") + "-" + LocalDateTime.now().format(ARCHIVE_TIMESTAMP)
                + "-" + snapshotId + options.compressionType().extension() + (options.encrypted() ? ".enc" : "");
    }

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

    public record NetworkBackupResult(String archiveName, boolean full, int scannedEntries, int archivedEntries,
                                      long bytes, List<String> warnings, BackupCatalog.Snapshot snapshot) {}
}
