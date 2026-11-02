package com.backup.core;

public enum CompressionType {
    ZIP(".zip"),
    GZIP(".bpgz");

    private final String extension;

    CompressionType(String extension) {
        this.extension = extension;
    }

    public String extension() {
        return extension;
    }
}
