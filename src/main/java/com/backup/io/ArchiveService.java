package com.backup.io;

import com.backup.core.BackupEntry;
import com.backup.core.BackupManifest;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public final class ArchiveService {
    private static final String ZIP_DATA_PREFIX = "data/";
    private static final String GZIP_MAGIC = "BACKPACK-GZIP-1";

    public void writeZipArchive(Path sourceRoot, Path output, BackupManifest manifest, Iterable<BackupEntry> archivedEntries)
            throws IOException {
        Files.createDirectories(output.getParent());
        try (ZipOutputStream zipOut = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(output)))) {
            ZipEntry manifestEntry = new ZipEntry(BackupManifest.ARCHIVE_ENTRY_NAME);
            zipOut.putNextEntry(manifestEntry);
            zipOut.write(manifest.toBytes());
            zipOut.closeEntry();

            for (BackupEntry entry : archivedEntries) {
                ZipEntry zipEntry = new ZipEntry(ZIP_DATA_PREFIX + entry.relativePath() + (entry.directory() ? "/" : ""));
                zipEntry.setLastModifiedTime(FileTime.fromMillis(entry.lastModifiedMillis()));
                zipOut.putNextEntry(zipEntry);
                if (!entry.directory()) {
                    Files.copy(sourceRoot.resolve(entry.relativePath()), zipOut);
                }
                zipOut.closeEntry();
            }
        }
    }

    public void writeGzipArchive(Path sourceRoot, Path output, BackupManifest manifest, Iterable<BackupEntry> archivedEntries)
            throws IOException {
        Files.createDirectories(output.getParent());
        List<BackupEntry> entries = new ArrayList<>();
        archivedEntries.forEach(entries::add);
        try (DataOutputStream dataOut = new DataOutputStream(new BufferedOutputStream(
                new GZIPOutputStream(Files.newOutputStream(output))))) {
            writeString(dataOut, GZIP_MAGIC);
            byte[] manifestBytes = manifest.toBytes();
            dataOut.writeInt(manifestBytes.length);
            dataOut.write(manifestBytes);

            dataOut.writeInt(entries.size());

            for (BackupEntry entry : entries) {
                writeString(dataOut, entry.relativePath());
                dataOut.writeBoolean(entry.directory());
                dataOut.writeLong(entry.size());
                dataOut.writeLong(entry.lastModifiedMillis());
                if (!entry.directory()) {
                    try (InputStream inputStream = Files.newInputStream(sourceRoot.resolve(entry.relativePath()))) {
                        inputStream.transferTo(dataOut);
                    }
                }
            }
        }
    }

    public BackupManifest restore(Path archive, Path destination) throws IOException {
        Files.createDirectories(destination);
        ArchiveKind kind = detectKind(archive);
        return switch (kind) {
            case ZIP -> restoreZip(archive, destination);
            case GZIP -> restoreGzip(archive, destination);
        };
    }

    private BackupManifest restoreZip(Path archive, Path destination) throws IOException {
        BackupManifest manifest = null;
        try (ZipInputStream zipIn = new ZipInputStream(new BufferedInputStream(Files.newInputStream(archive)))) {
            ZipEntry entry;
            while ((entry = zipIn.getNextEntry()) != null) {
                String name = entry.getName();
                if (BackupManifest.ARCHIVE_ENTRY_NAME.equals(name)) {
                    manifest = BackupManifest.fromString(readAllUtf8(zipIn));
                } else if (name.startsWith(ZIP_DATA_PREFIX)) {
                    Path output = safeResolve(destination, name.substring(ZIP_DATA_PREFIX.length()));
                    if (entry.isDirectory()) {
                        Files.createDirectories(output);
                    } else {
                        Files.createDirectories(output.getParent());
                        try (OutputStream outputStream = Files.newOutputStream(output)) {
                            zipIn.transferTo(outputStream);
                        }
                    }
                }
                zipIn.closeEntry();
            }
        }

        if (manifest == null) {
            throw new IOException("Archive does not contain a Backup manifest.");
        }
        applyManifestMetadata(destination, manifest);
        return manifest;
    }

    private BackupManifest restoreGzip(Path archive, Path destination) throws IOException {
        BackupManifest manifest;
        try (DataInputStream dataIn = new DataInputStream(new BufferedInputStream(
                new GZIPInputStream(Files.newInputStream(archive))))) {
            String magic = readString(dataIn);
            if (!GZIP_MAGIC.equals(magic)) {
                throw new IOException("Unsupported Backup GZIP archive.");
            }
            int manifestLength = dataIn.readInt();
            if (manifestLength <= 0 || manifestLength > 64 * 1024 * 1024) {
                throw new IOException("Invalid manifest length: " + manifestLength);
            }
            byte[] manifestBytes = dataIn.readNBytes(manifestLength);
            if (manifestBytes.length != manifestLength) {
                throw new IOException("Unexpected end of GZIP archive while reading the manifest.");
            }
            manifest = BackupManifest.fromString(new String(manifestBytes, StandardCharsets.UTF_8));
            int count = dataIn.readInt();
            if (count < 0) {
                throw new IOException("Invalid GZIP archive entry count: " + count);
            }
            for (int i = 0; i < count; i++) {
                String relativePath = readString(dataIn);
                boolean directory = dataIn.readBoolean();
                long size = dataIn.readLong();
                dataIn.readLong();
                Path output = safeResolve(destination, relativePath);
                if (directory) {
                    Files.createDirectories(output);
                } else {
                    Files.createDirectories(output.getParent());
                    copyExactly(dataIn, output, size);
                }
            }
        }
        applyManifestMetadata(destination, manifest);
        return manifest;
    }

    private void copyExactly(InputStream inputStream, Path output, long bytes) throws IOException {
        if (bytes < 0) {
            throw new IOException("Invalid entry size: " + bytes);
        }
        byte[] buffer = new byte[64 * 1024];
        long remaining = bytes;
        try (OutputStream outputStream = Files.newOutputStream(output)) {
            while (remaining > 0) {
                int read = inputStream.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (read == -1) {
                    throw new IOException("Unexpected end of archive.");
                }
                outputStream.write(buffer, 0, read);
                remaining -= read;
            }
        }
    }

    private void applyManifestMetadata(Path destination, BackupManifest manifest) {
        Map<String, BackupEntry> entries = manifest.entriesByPath();
        entries.values().forEach(entry -> {
            Path output = destination.resolve(entry.relativePath()).normalize();
            if (!output.startsWith(destination.normalize()) || !Files.exists(output)) {
                return;
            }
            try {
                Files.setLastModifiedTime(output, FileTime.fromMillis(entry.lastModifiedMillis()));
            } catch (IOException ignored) {
                // Best effort: some file systems reject restored timestamps.
            }
            if (!entry.permissions().isBlank()) {
                try {
                    Set<PosixFilePermission> permissions = PosixFilePermissions.fromString(entry.permissions());
                    Files.setPosixFilePermissions(output, permissions);
                } catch (IllegalArgumentException | UnsupportedOperationException | IOException ignored) {
                    // POSIX permissions are not available on every platform.
                }
            }
        });
    }

    private ArchiveKind detectKind(Path archive) throws IOException {
        byte[] header;
        try (InputStream inputStream = Files.newInputStream(archive)) {
            header = inputStream.readNBytes(4);
        }
        if (header.length >= 4 && header[0] == 'P' && header[1] == 'K') {
            return ArchiveKind.ZIP;
        }
        if (header.length >= 2 && (header[0] & 0xFF) == 0x1F && (header[1] & 0xFF) == 0x8B) {
            return ArchiveKind.GZIP;
        }
        throw new IOException("Unsupported archive type: " + archive);
    }

    private Path safeResolve(Path destination, String relativePath) throws IOException {
        Path resolved = destination.resolve(relativePath).normalize();
        if (!resolved.startsWith(destination.normalize())) {
            throw new IOException("Archive entry escapes restore directory: " + relativePath);
        }
        return resolved;
    }

    private String readAllUtf8(InputStream inputStream) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        inputStream.transferTo(outputStream);
        return outputStream.toString(StandardCharsets.UTF_8);
    }

    private void writeString(DataOutputStream outputStream, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        outputStream.writeInt(bytes.length);
        outputStream.write(bytes);
    }

    private String readString(DataInputStream inputStream) throws IOException {
        int length = inputStream.readInt();
        if (length < 0 || length > 1024 * 1024) {
            throw new IOException("Invalid string length: " + length);
        }
        byte[] bytes = inputStream.readNBytes(length);
        if (bytes.length != length) {
            throw new IOException("Unexpected end of archive while reading a string.");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private enum ArchiveKind {
        ZIP,
        GZIP
    }
}
