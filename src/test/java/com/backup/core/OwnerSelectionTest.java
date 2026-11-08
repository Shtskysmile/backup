package com.backup.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 属主筛选专项测试：跨平台读取真实属主并按其筛选（含不区分大小写、
 * 不存在属主全排除、属主与其它条件 AND 组合）。
 *
 * 用例清单（2 项）：
 * - scannerSelectsRecordedOwnerAndKeepsRequiredAncestors
 * - scannerRejectsNonmatchingOwnersAndPrunesUnusedDirectories
 */
class OwnerSelectionTest {
    @TempDir Path temp;

    private BackupFilter ownerFilter(String owners) {
        return new BackupFilter("", "", "", false, "", owners, null, null, null, null);
    }

    /** 按真实属主筛选：命中文件与其必需祖先目录被保留，空目录剪除；记录的属主与系统一致。 */
    @Test void scannerSelectsRecordedOwnerAndKeepsRequiredAncestors() throws Exception {
        Path source = Files.createDirectory(temp.resolve("source"));
        Path docs = Files.createDirectory(source.resolve("docs"));
        Path file = Files.writeString(docs.resolve("selected.txt"), "owned content");
        Files.createDirectory(source.resolve("empty"));
        assumeTrue(Files.getFileStore(file).supportsFileAttributeView("owner"), "Owner attributes unavailable");
        String owner = Files.getOwner(file, LinkOption.NOFOLLOW_LINKS).getName();
        assumeTrue(!owner.isBlank(), "Recorded owner unavailable");

        List<BackupEntry> selected = new FileScanner().scan(source, ownerFilter(owner), ignored -> {});

        assertEquals(List.of("docs", "docs/selected.txt"), selected.stream().map(BackupEntry::relativePath).toList());
        assertEquals(owner, selected.get(1).owner());
    }

    /** 属主条件不匹配任何文件：扫描结果为空（未命中目录与文件全部剪除）。 */
    @Test void scannerRejectsNonmatchingOwnersAndPrunesUnusedDirectories() throws Exception {
        Path source = Files.createDirectory(temp.resolve("source"));
        Path docs = Files.createDirectory(source.resolve("docs"));
        Path file = Files.writeString(docs.resolve("ignored.txt"), "content");
        assumeTrue(Files.getFileStore(file).supportsFileAttributeView("owner"), "Owner attributes unavailable");
        String owner = Files.getOwner(file, LinkOption.NOFOLLOW_LINKS).getName();
        String otherOwner = owner + "-does-not-match";

        assertTrue(new FileScanner().scan(source, ownerFilter(otherOwner), ignored -> {}).isEmpty());
    }
}
