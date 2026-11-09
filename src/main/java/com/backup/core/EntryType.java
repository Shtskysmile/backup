package com.backup.core;

/**
 * 条目类型八分类。由 FileScanner 按文件属性与 unix 类型位判定；
 * ArchiveService 据此决定载荷有无、重建方式与清单校验规则。
 */
public enum EntryType {
    FILE, DIRECTORY, SYMBOLIC_LINK, HARD_LINK, FIFO, CHARACTER_DEVICE, BLOCK_DEVICE, SOCKET
}
