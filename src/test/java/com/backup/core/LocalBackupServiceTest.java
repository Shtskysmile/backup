package com.backup.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.backup.testing.FileSystemCapabilities.*;

/**
 * 本地备份服务级测试：筛选后硬链接载荷选择与父目录保留、任务隔离、
 * 保留轮换 12 连备、失败不淘汰、属主扫描筛选、源别名防内嵌目标。
 *
 * 用例清单（6 项）：
 * - filtersBeforeChoosingHardlinkPayloadAndKeepsAncestors
 * - changingTaskOrRestartingNeverReusesAnotherBaseline
 * - retentionRollsGroupsAndKeepsWholeLatestGroup
 * - failedBackupDoesNotPrunePreviousSuccess
 * - metadataOnlyChangesAreIncludedInIncremental
 * - sourceAliasCannotPlaceDestinationInsideSource
 */
class LocalBackupServiceTest {
    @TempDir Path temp;
    /** 统一参数：ZIP、目标=backups、可配增量/保留/筛选。 */
    private BackupOptions options(Path source, String name, boolean incremental, int keep, BackupFilter filter) {
        return new BackupOptions(source, temp.resolve("backups"), name, CompressionType.ZIP, incremental, false, null, filter, keep);
    }

    /** 筛选先行：b.txt 被选中而互为硬链接的 a.txt 被筛掉后，载荷只带 b.txt 不退化成普通拷贝；命中条目的祖先目录保留、空目录剪除。 */
    @Test void filtersBeforeChoosingHardlinkPayloadAndKeepsAncestors() throws Exception {
        assumeHardLinks(temp);
        Path source = Files.createDirectories(temp.resolve("source/docs"));
        Files.writeString(source.resolve("a.txt"), "shared");
        Files.createLink(source.resolve("b.txt"), source.resolve("a.txt"));
        Files.writeString(source.resolve("ignored.pdf"), "no");
        Files.createDirectories(source.resolve("empty"));
        BackupFilter filter = new BackupFilter("docs/b.txt", "", "txt", false, "", null, null, null, null);
        BackupService service = new BackupService();
        BackupResult result = service.backup(options(source.getParent(), "filter", false, 0, filter));
        Path out = temp.resolve("out");
        service.restore(result.archivePath(), out, null);
        assertEquals("shared", Files.readString(out.resolve("docs/b.txt")));
        assertFalse(Files.exists(out.resolve("docs/a.txt")));
        assertFalse(Files.exists(out.resolve("docs/empty")));
        assertEquals(2, BackupManifest.load(result.manifestPath()).entries().size());
    }

    /** 基线隔离：换源目录、换任务名、换服务实例（模拟重启）都不续链，一律全量。 */
    @Test void changingTaskOrRestartingNeverReusesAnotherBaseline() throws Exception {
        Path first = Files.createDirectory(temp.resolve("first"));
        Path second = Files.createDirectory(temp.resolve("second"));
        Files.writeString(first.resolve("file"), "same"); Files.writeString(second.resolve("file"), "same");
        BackupService service = new BackupService();
        service.backup(options(first, "first", true, 0, BackupFilter.all()));
        BackupResult b = service.backup(options(second, "second", true, 0, BackupFilter.all()));
        assertFalse(BackupManifest.load(b.manifestPath()).incremental());
        BackupResult c = new BackupService().backup(options(first, "first", true, 0, BackupFilter.all()));
        assertFalse(BackupManifest.load(c.manifestPath()).incremental());
        Path out = temp.resolve("out"); service.restore(c.archivePath(), out, null);
        assertEquals("same", Files.readString(out.resolve("file")));
    }

    /** 12 连备 keepGroups=1：前 10 个归档被整组淘汰，第 11/12 个保留且构成完整链（全量→增量，父子关系正确）。 */
    @Test void retentionRollsGroupsAndKeepsWholeLatestGroup() throws Exception {
        Path source = Files.createDirectory(temp.resolve("source"));
        Files.writeString(source.resolve("file"), "content");
        BackupService service = new BackupService(); List<BackupResult> results = new ArrayList<>();
        BackupOptions options = options(source, "chain", true, 1, BackupFilter.all());
        for (int i=0; i<12; i++) results.add(service.backup(options));
        for (int i=0; i<10; i++) assertFalse(Files.exists(results.get(i).archivePath()), "old=" + results.get(i).archivePath() + " latest=" + results.get(11).archivePath());
        assertTrue(Files.exists(results.get(10).archivePath()));
        assertTrue(Files.exists(results.get(11).archivePath()));
        BackupManifest full = BackupManifest.load(results.get(10).manifestPath());
        BackupManifest incremental = BackupManifest.load(results.get(11).manifestPath());
        assertFalse(full.incremental()); assertTrue(incremental.incremental());
        assertEquals(full.snapshot().snapshotId(), incremental.snapshot().parentId());
        assertEquals(full.snapshot().groupId(), incremental.snapshot().groupId());
    }

    /** 备份中途失败（源目录消失）：此前成功备份的归档与清单原样保留，淘汰不执行。 */
    @Test void failedBackupDoesNotPrunePreviousSuccess() throws Exception {
        Path source = Files.createDirectory(temp.resolve("source")); Files.writeString(source.resolve("file"), "ok");
        BackupService service = new BackupService(); BackupOptions options = options(source, "chain", false, 1, BackupFilter.all());
        BackupResult first = service.backup(options);
        Files.delete(source.resolve("file")); Files.delete(source);
        assertThrows(IOException.class, () -> service.backup(options));
        assertTrue(Files.exists(first.archivePath())); assertTrue(Files.exists(first.manifestPath()));
    }

    /** 仅权限/时间变化也触发增量：增量含 1 条目，还原后权限与修改时间精确回填。 */
    @Test void metadataOnlyChangesAreIncludedInIncremental() throws Exception {
        Path source = Files.createDirectory(temp.resolve("source")); Path file = Files.writeString(source.resolve("file"), "same");
        assumePosix(file);
        BackupService service = new BackupService(); BackupOptions options = options(source, "chain", true, 0, BackupFilter.all());
        service.backup(options);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        Files.setLastModifiedTime(file, FileTime.fromMillis(1_600_000_000_000L));
        BackupResult second = service.backup(options);
        assertEquals(1, second.archivedEntries());
        Path out = temp.resolve("out"); service.restore(second.archivePath(), out, null);
        assertEquals(1_600_000_000_000L, Files.getLastModifiedTime(out.resolve("file")).toMillis());
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(out.resolve("file"))));
    }

    /** 通过符号链接别名偷渡目标进源目录：toRealPath 双重校验识破并拒绝。 */
    @Test void sourceAliasCannotPlaceDestinationInsideSource() throws Exception {
        assumeDirectorySymbolicLinks(temp);
        Path source = Files.createDirectory(temp.resolve("source"));
        Path alias = Files.createSymbolicLink(temp.resolve("alias"), source);
        assertThrows(IOException.class, () -> new BackupService().backup(new BackupOptions(alias, source.resolve("nested"), "bad", CompressionType.ZIP, false, false, null)));
    }
}
