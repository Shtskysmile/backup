package com.backup.core;

import java.util.List;

/**
 * 还原结果：清单（含快照身份）+ 警告列表。警告分三类——平台元数据不支持、
 * 增量基线缺失或不匹配、特殊文件未重建；数据本身要么完整还原要么整体失败。
 */
public record RestoreResult(BackupManifest manifest, List<String> warnings) {
    public RestoreResult { warnings = List.copyOf(warnings); }
}
