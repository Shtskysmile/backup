package com.backup.core;

import com.backup.crypto.EncryptionService;
import com.backup.io.ArchiveService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class BackupService {
    // Keep the on-disk manifest name stable so existing incremental backups remain usable.
    private static final String LATEST_MANIFEST_NAME = ".backpack-manifest.properties";
    private static final DateTimeFormatter ARCHIVE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final FileScanner fileScanner;
    private final ArchiveService archiveService;
    private final EncryptionService encryptionService;

    public BackupService() {
        this(new FileScanner(), new ArchiveService(), new EncryptionService());
    }

    public BackupService(FileScanner fileScanner, ArchiveService archiveService, EncryptionService encryptionService) {
        this.fileScanner = fileScanner;
        this.archiveService = archiveService;
        this.encryptionService = encryptionService;
    }

    public BackupResult backup(BackupOptions options) throws IOException {
        Instant started = Instant.now();
        Path source = options.sourceDirectory().toAbsolutePath().normalize();
        Path destination = options.destinationDirectory().toAbsolutePath().normalize();
        if (source.equals(destination) || destination.startsWith(source)) {
            throw new IOException("Destination directory must not be inside the source directory.");
        }
        Files.createDirectories(destination);

        List<BackupEntry> scannedEntries = fileScanner.scan(source);
        BackupManifest previousManifest = loadPreviousManifest(destination, options.incremental());
        List<BackupEntry> archivedEntries = filterEntries(scannedEntries, previousManifest, options.incremental());
        BackupManifest manifest = new BackupManifest(
                source.toString(),
                Instant.now(),
                options.compressionType(),
                options.incremental(),
                options.encrypted(),
                scannedEntries
        );

        String fileName = archiveFileName(options);
        Path archivePath = uniqueArchivePath(destination, fileName);
        Path clearArchivePath = options.encrypted()
                ? Files.createTempFile(destination, "backup-clear-", options.compressionType().extension())
                : archivePath;

        try {
            writeArchive(source, clearArchivePath, manifest, archivedEntries, options.compressionType());
            if (options.encrypted()) {
                encryptionService.encrypt(clearArchivePath, archivePath, options.passphrase());
            }
        } finally {
            if (options.encrypted()) {
                Files.deleteIfExists(clearArchivePath);
            }
        }

        Path latestManifest = destination.resolve(LATEST_MANIFEST_NAME);
        manifest.store(latestManifest);
        Path archiveManifest = destination.resolve(archivePath.getFileName().toString() + ".manifest.properties");
        manifest.store(archiveManifest);

        long archivedBytes = Files.size(archivePath);
        return new BackupResult(
                archivePath,
                archiveManifest,
                scannedEntries.size(),
                archivedEntries.size(),
                archivedBytes,
                Duration.between(started, Instant.now()),
                options.encrypted()
        );
    }

    public BackupManifest restore(Path archive, Path destination, char[] passphrase) throws IOException {
        Path archiveToRead = archive.toAbsolutePath().normalize();
        Path tempFile = null;
        try {
            if (encryptionService.isEncrypted(archiveToRead)) {
                if (passphrase == null || passphrase.length == 0) {
                    throw new IOException("Encrypted archive requires a passphrase.");
                }
                tempFile = encryptionService.decryptToTempFile(archiveToRead, passphrase);
                archiveToRead = tempFile;
            }
            return archiveService.restore(archiveToRead, destination.toAbsolutePath().normalize());
        } finally {
            if (tempFile != null) {
                Files.deleteIfExists(tempFile);
            }
        }
    }

    private BackupManifest loadPreviousManifest(Path destination, boolean incremental) throws IOException {
        if (!incremental) {
            return null;
        }
        Path latestManifest = destination.resolve(LATEST_MANIFEST_NAME);
        return Files.isRegularFile(latestManifest) ? BackupManifest.load(latestManifest) : null;
    }

    private List<BackupEntry> filterEntries(List<BackupEntry> scannedEntries, BackupManifest previousManifest, boolean incremental) {
        if (!incremental || previousManifest == null) {
            return scannedEntries;
        }
        Map<String, BackupEntry> previousByPath = previousManifest.entriesByPath();
        List<BackupEntry> changed = new ArrayList<>();
        for (BackupEntry entry : scannedEntries) {
            BackupEntry previous = previousByPath.get(entry.relativePath());
            if (entry.directory() || !entry.sameContentAs(previous)) {
                changed.add(entry);
            }
        }
        return changed;
    }

    private void writeArchive(
            Path source,
            Path archivePath,
            BackupManifest manifest,
            List<BackupEntry> archivedEntries,
            CompressionType compressionType
    ) throws IOException {
        switch (compressionType) {
            case ZIP -> archiveService.writeZipArchive(source, archivePath, manifest, archivedEntries);
            case GZIP -> archiveService.writeGzipArchive(source, archivePath, manifest, archivedEntries);
        }
    }

    private String archiveFileName(BackupOptions options) {
        String safeName = options.backupName().replaceAll("[^A-Za-z0-9._-]+", "-");
        if (safeName.isBlank()) {
            safeName = "backup";
        }
        String mode = options.incremental() ? "incremental" : "full";
        String timestamp = LocalDateTime.now().format(ARCHIVE_TIMESTAMP);
        return safeName + "-" + mode + "-" + timestamp + options.compressionType().extension() + (options.encrypted() ? ".enc" : "");
    }

    private Path uniqueArchivePath(Path destination, String fileName) {
        Path candidate = destination.resolve(fileName);
        int suffix = 1;
        while (Files.exists(candidate) || Files.exists(destination.resolve(fileName + ".manifest.properties"))) {
            candidate = destination.resolve(fileName.replaceFirst("(\\.[^.]+(?:\\.enc)?)$", "-%d$1".formatted(suffix++)));
        }
        return candidate;
    }
}
