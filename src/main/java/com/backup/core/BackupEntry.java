package com.backup.core;

import java.util.Objects;

/**
 * 一个备份条目的不可变快照描述，也是清单序列化的最小单元（模块间契约）。
 *
 * <p>
 * compact constructor 做三件事：null 防御（字符串字段统一归一为空串）、
 * type 缺省推导（directory 布尔值与枚举互相校验）、拒绝非法组合
 * （directory 标志与类型不一致、负尺寸）。坏数据在构造时即被拒绝，不会流到清单或归档。
 * </p>
 *
 * <p>
 * {@link #sameContentAs} 是增量判定的核心比较器：类型、尺寸、修改时间、SHA-256、
 * 链接目标、权限、属主、组八个字段全等才算"未变"——任何元数据变化都会触发增量归档。
 * </p>
 */
public record BackupEntry(
        String relativePath, boolean directory, long size, long lastModifiedMillis,
        String sha256, String permissions, String owner, String group,
        EntryType type, String linkTarget, Long lastAccessMillis, Long creationMillis) {
    /** v1 兼容构造器：老清单/测试没有类型与链接概念，按 directory 推导。 */
    public BackupEntry(String path, boolean directory, long size, long modified,
            String hash, String permissions, String owner, String group) {
        this(path, directory, size, modified, hash, permissions, owner, group,
                directory ? EntryType.DIRECTORY : EntryType.FILE, "", null, null);
    }

    public BackupEntry {
        Objects.requireNonNull(relativePath, "relativePath");
        type = type == null ? (directory ? EntryType.DIRECTORY : EntryType.FILE) : type;
        if (directory != (type == EntryType.DIRECTORY) || size < 0) {
            // 一致性拒绝：布尔目录标志与枚举类型互斥校验，负尺寸非法——坏数据进不了清单。
            throw new IllegalArgumentException("Invalid file entry: " + relativePath);
        }
        sha256 = Objects.requireNonNullElse(sha256, "");
        permissions = Objects.requireNonNullElse(permissions, "");
        owner = Objects.requireNonNullElse(owner, "");
        group = Objects.requireNonNullElse(group, "");
        linkTarget = Objects.requireNonNullElse(linkTarget, "");
    }

    /** 增量判定核心比较器：八个字段全等才算未变（见类注释）。 */
    public boolean sameContentAs(BackupEntry other) {
        return other != null && type == other.type && size == other.size
                && lastModifiedMillis == other.lastModifiedMillis
                && sha256.equals(other.sha256) && linkTarget.equals(other.linkTarget)
                && permissions.equals(other.permissions) && owner.equals(other.owner) && group.equals(other.group);
    }
}
