package com.backup.core;

/**
 * 压缩算法枚举：新增算法时 switch 会在编译期穷尽检查，避免遗漏分支。
 * GZIP 扩展名 .bpgz 表明它是本产品自定义容器（清单+多条目）而非单纯压缩流。
 */
public enum CompressionType {
    ZIP(".zip"),
    GZIP(".bpgz");

    private final String extension;

    CompressionType(String extension) {
        this.extension = extension;
    }

    /** 归档扩展名（ZIP=.zip，GZIP=.bpgz 表明自定义容器）。 */
    public String extension() {
        return extension;
    }
}
