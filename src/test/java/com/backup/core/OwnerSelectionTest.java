package com.backup.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class OwnerSelectionTest {
    @TempDir Path temp;

    private BackupFilter ownerFilter(String owners) {
        return new BackupFilter("", "", "", false, "", owners, null, null, null, null);
    }

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
