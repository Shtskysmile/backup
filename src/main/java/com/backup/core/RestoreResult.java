package com.backup.core;

import java.util.List;

public record RestoreResult(BackupManifest manifest, List<String> warnings) {
    public RestoreResult { warnings = List.copyOf(warnings); }
}
