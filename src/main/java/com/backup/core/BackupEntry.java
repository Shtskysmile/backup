package com.backup.core;

import java.util.Objects;

public record BackupEntry(
        String relativePath, boolean directory, long size, long lastModifiedMillis,
        String sha256, String permissions, String owner, String group,
        EntryType type, String linkTarget, Long lastAccessMillis, Long creationMillis
) {
    public BackupEntry(String path, boolean directory, long size, long modified,
                       String hash, String permissions, String owner, String group) {
        this(path, directory, size, modified, hash, permissions, owner, group,
                directory ? EntryType.DIRECTORY : EntryType.FILE, "", null, null);
    }

    public BackupEntry {
        Objects.requireNonNull(relativePath, "relativePath");
        type = type == null ? (directory ? EntryType.DIRECTORY : EntryType.FILE) : type;
        if (directory != (type == EntryType.DIRECTORY) || size < 0) {
            throw new IllegalArgumentException("Invalid file entry: " + relativePath);
        }
        sha256 = Objects.requireNonNullElse(sha256, "");
        permissions = Objects.requireNonNullElse(permissions, "");
        owner = Objects.requireNonNullElse(owner, "");
        group = Objects.requireNonNullElse(group, "");
        linkTarget = Objects.requireNonNullElse(linkTarget, "");
    }

    public boolean sameContentAs(BackupEntry other) {
        return other != null && type == other.type && size == other.size
                && lastModifiedMillis == other.lastModifiedMillis
                && sha256.equals(other.sha256) && linkTarget.equals(other.linkTarget)
                && permissions.equals(other.permissions) && owner.equals(other.owner) && group.equals(other.group);
    }
}
