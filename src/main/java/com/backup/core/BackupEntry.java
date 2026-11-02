package com.backup.core;

import java.util.Objects;

public record BackupEntry(
        String relativePath,
        boolean directory,
        long size,
        long lastModifiedMillis,
        String sha256,
        String permissions,
        String owner,
        String group
) {
    public BackupEntry {
        Objects.requireNonNull(relativePath, "relativePath");
        sha256 = sha256 == null ? "" : sha256;
        permissions = permissions == null ? "" : permissions;
        owner = owner == null ? "" : owner;
        group = group == null ? "" : group;
    }

    public boolean sameContentAs(BackupEntry other) {
        if (other == null || directory != other.directory || size != other.size) {
            return false;
        }
        if (directory) {
            return lastModifiedMillis == other.lastModifiedMillis;
        }
        return sha256.equals(other.sha256);
    }
}
