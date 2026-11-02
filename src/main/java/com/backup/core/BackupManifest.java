package com.backup.core;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class BackupManifest {
    public static final String ARCHIVE_ENTRY_NAME = "BACKPACK-MANIFEST.properties";
    private static final String VERSION = "2";

    private final String sourceRoot;
    private final Instant createdAt;
    private final CompressionType compressionType;
    private final boolean incremental;
    private final boolean encrypted;
    private final List<BackupEntry> entries;
    private BackupCatalog.Snapshot snapshot;

    public BackupManifest(
            String sourceRoot,
            Instant createdAt,
            CompressionType compressionType,
            boolean incremental,
            boolean encrypted,
            List<BackupEntry> entries
    ) {
        this.sourceRoot = Objects.requireNonNull(sourceRoot, "sourceRoot");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.compressionType = Objects.requireNonNull(compressionType, "compressionType");
        this.incremental = incremental;
        this.encrypted = encrypted;
        this.entries = List.copyOf(entries);
    }

    public BackupManifest withSnapshot(BackupCatalog.Snapshot value) {
        this.snapshot = value;
        return this;
    }

    public BackupCatalog.Snapshot snapshot() { return snapshot; }

    public String sourceRoot() {
        return sourceRoot;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public CompressionType compressionType() {
        return compressionType;
    }

    public boolean incremental() {
        return incremental;
    }

    public boolean encrypted() {
        return encrypted;
    }

    public List<BackupEntry> entries() {
        return entries;
    }

    public Map<String, BackupEntry> entriesByPath() {
        return entries.stream().collect(Collectors.toMap(BackupEntry::relativePath, Function.identity(), (left, right) -> right));
    }

    public String toPropertiesString() throws IOException {
        Properties properties = toProperties();
        try (StringWriter writer = new StringWriter()) {
            properties.store(writer, "Backup manifest");
            return writer.toString();
        }
    }

    public byte[] toBytes() throws IOException {
        return toPropertiesString().getBytes(StandardCharsets.UTF_8);
    }

    public void store(Path manifestPath) throws IOException {
        Path target = manifestPath.toAbsolutePath().normalize();
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), ".manifest-", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                toProperties().store(writer, "Backup manifest");
            }
            Files.move(temporary, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }

    private Properties toProperties() {
        Properties properties = new Properties();
        properties.setProperty("version", VERSION);
        properties.setProperty("sourceRoot", sourceRoot);
        properties.setProperty("createdAt", createdAt.toString());
        properties.setProperty("compressionType", compressionType.name());
        properties.setProperty("incremental", Boolean.toString(incremental));
        properties.setProperty("encrypted", Boolean.toString(encrypted));
        properties.setProperty("entry.count", Integer.toString(entries.size()));
        if (snapshot != null) {
            properties.setProperty("taskId", snapshot.taskId());
            properties.setProperty("snapshotId", snapshot.snapshotId());
            properties.setProperty("groupId", snapshot.groupId());
            properties.setProperty("parentId", snapshot.parentId());
            properties.setProperty("sequence", Integer.toString(snapshot.sequence()));
            properties.setProperty("archiveName", snapshot.archiveName());
        }
        for (int i = 0; i < entries.size(); i++) {
            BackupEntry entry = entries.get(i);
            String prefix = "entry." + i + ".";
            properties.setProperty(prefix + "path", entry.relativePath());
            properties.setProperty(prefix + "directory", Boolean.toString(entry.directory()));
            properties.setProperty(prefix + "size", Long.toString(entry.size()));
            properties.setProperty(prefix + "lastModifiedMillis", Long.toString(entry.lastModifiedMillis()));
            properties.setProperty(prefix + "sha256", entry.sha256());
            properties.setProperty(prefix + "permissions", entry.permissions());
            properties.setProperty(prefix + "owner", entry.owner());
            properties.setProperty(prefix + "group", entry.group());
            properties.setProperty(prefix + "type", entry.type().name());
            properties.setProperty(prefix + "linkTarget", entry.linkTarget());
            if (entry.lastAccessMillis() != null) properties.setProperty(prefix + "lastAccessMillis", entry.lastAccessMillis().toString());
            if (entry.creationMillis() != null) properties.setProperty(prefix + "creationMillis", entry.creationMillis().toString());
        }
        return properties;
    }

    public static BackupManifest load(Path manifestPath) throws IOException {
        try (Reader reader = Files.newBufferedReader(manifestPath, StandardCharsets.UTF_8)) {
            try { return load(reader); }
            catch (IllegalArgumentException | NullPointerException e) { throw new IOException("Invalid backup manifest", e); }
        }
    }

    public static BackupManifest fromString(String content) throws IOException {
        try { return load(new StringReader(content)); }
        catch (IllegalArgumentException | NullPointerException e) { throw new IOException("Invalid backup manifest", e); }
    }

    private static BackupManifest load(Reader reader) throws IOException {
        Properties properties = new Properties();
        properties.load(reader);
        String version = properties.getProperty("version");
        if (!VERSION.equals(version) && !"1".equals(version)) {
            throw new IOException("Unsupported manifest version: " + version);
        }
        int count = Integer.parseInt(properties.getProperty("entry.count", "0"));
        if (count < 0 || count > 1_000_000) throw new IOException("Invalid manifest entry count");
        List<BackupEntry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String prefix = "entry." + i + ".";
            entries.add(new BackupEntry(
                    properties.getProperty(prefix + "path", ""),
                    Boolean.parseBoolean(properties.getProperty(prefix + "directory", "false")),
                    Long.parseLong(properties.getProperty(prefix + "size", "0")),
                    Long.parseLong(properties.getProperty(prefix + "lastModifiedMillis", "0")),
                    properties.getProperty(prefix + "sha256", ""),
                    properties.getProperty(prefix + "permissions", ""),
                    properties.getProperty(prefix + "owner", ""),
                    properties.getProperty(prefix + "group", ""),
                    EntryType.valueOf(properties.getProperty(prefix + "type",
                            Boolean.parseBoolean(properties.getProperty(prefix + "directory")) ? "DIRECTORY" : "FILE")),
                    properties.getProperty(prefix + "linkTarget", ""),
                    nullableLong(properties, prefix + "lastAccessMillis"),
                    nullableLong(properties, prefix + "creationMillis")
            ));
        }
        BackupManifest manifest = new BackupManifest(
                properties.getProperty("sourceRoot", ""),
                Instant.parse(properties.getProperty("createdAt", Instant.EPOCH.toString())),
                CompressionType.valueOf(properties.getProperty("compressionType", CompressionType.ZIP.name())),
                Boolean.parseBoolean(properties.getProperty("incremental", "false")),
                Boolean.parseBoolean(properties.getProperty("encrypted", "false")),
                entries
        );
        if (properties.containsKey("snapshotId")) {
            manifest.withSnapshot(new BackupCatalog.Snapshot(properties.getProperty("taskId"),
                    properties.getProperty("snapshotId"), properties.getProperty("groupId"),
                    properties.getProperty("parentId", ""), Integer.parseInt(properties.getProperty("sequence")),
                    properties.getProperty("archiveName"), manifest.createdAt()));
        }
        return manifest;
    }

    private static Long nullableLong(Properties properties, String key) {
        String value = properties.getProperty(key);
        return value == null ? null : Long.valueOf(value);
    }
}
