package com.backup.core;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileOwnerAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class FileScanner {
    public List<BackupEntry> scan(Path root) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalizedRoot)) {
            throw new IOException("Source is not a directory: " + normalizedRoot);
        }

        List<BackupEntry> entries = new ArrayList<>();
        Files.walkFileTree(normalizedRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (!normalizedRoot.equals(dir)) {
                    entries.add(toEntry(normalizedRoot, dir, attrs, true));
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (attrs.isRegularFile()) {
                    entries.add(toEntry(normalizedRoot, file, attrs, false));
                }
                return FileVisitResult.CONTINUE;
            }
        });
        entries.sort(Comparator.comparing(BackupEntry::relativePath));
        return entries;
    }

    private BackupEntry toEntry(Path root, Path path, BasicFileAttributes attrs, boolean directory) throws IOException {
        PosixMetadata posixMetadata = readPosixMetadata(path);
        String relativePath = root.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
        return new BackupEntry(
                relativePath,
                directory,
                directory ? 0 : attrs.size(),
                attrs.lastModifiedTime().toMillis(),
                directory ? "" : HashUtils.sha256(path),
                posixMetadata.permissions(),
                posixMetadata.owner(),
                posixMetadata.group()
        );
    }

    private PosixMetadata readPosixMetadata(Path path) {
        try {
            PosixFileAttributes attrs = Files.readAttributes(path, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return new PosixMetadata(
                    PosixFilePermissions.toString(attrs.permissions()),
                    attrs.owner().getName(),
                    attrs.group().getName()
            );
        } catch (UnsupportedOperationException | IOException ignored) {
            return new PosixMetadata("", readOwner(path), "");
        }
    }

    private String readOwner(Path path) {
        try {
            FileOwnerAttributeView view = Files.getFileAttributeView(path, FileOwnerAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            return view == null || view.getOwner() == null ? "" : view.getOwner().getName();
        } catch (IOException | UnsupportedOperationException ignored) {
            return "";
        }
    }

    private record PosixMetadata(String permissions, String owner, String group) {
    }
}
