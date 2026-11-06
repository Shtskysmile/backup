package com.backup.testing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Capability checks use separate scratch paths so failures in test fixtures remain failures. */
/**
 * 平台能力探测（测试基础设施）：一次性探测 POSIX 视图、符号/硬链接创建、
 * 文件身份、mkfifo 可用性，供各测试套件 assumeTrue 守卫——
 * 支持的平台实跑，不支持的跳过并在测试报告中如实说明。
 */
public final class FileSystemCapabilities {
    private FileSystemCapabilities() {}

    public static void assumePosix(Path path) {
        assumeTrue(Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS) != null,
                "POSIX attributes unavailable on the test filesystem");
    }

    public static void assumeFileKeys(Path sample) throws IOException {
        assumeTrue(Files.readAttributes(sample, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey() != null,
                "File identity (fileKey) unavailable; hardlink detection cannot preserve identity");
    }

    public static void assumeHardLinks(Path temp) throws IOException {
        Path probe = Files.createTempDirectory(temp, "hardlink-capability-");
        Path target = probe.resolve("target");
        Path link = probe.resolve("link");
        try {
            Files.writeString(target, "probe");
            try {
                Files.createLink(link, target);
            } catch (IOException | UnsupportedOperationException ex) {
                assumeTrue(false, "Hardlink creation unavailable: " + ex);
            }
        } finally {
            Files.deleteIfExists(link);
            Files.deleteIfExists(target);
            Files.delete(probe);
        }
    }

    public static void assumeSymbolicLinks(Path temp) throws IOException {
        assumeSymbolicLinks(temp, false);
    }

    public static void assumeDirectorySymbolicLinks(Path temp) throws IOException {
        assumeSymbolicLinks(temp, true);
    }

    private static void assumeSymbolicLinks(Path temp, boolean directory) throws IOException {
        Path probe = Files.createTempDirectory(temp, "symlink-capability-");
        Path target = probe.resolve("target");
        Path link = probe.resolve("link");
        try {
            if (directory) Files.createDirectory(target);
            else Files.writeString(target, "probe");
            try {
                Files.createSymbolicLink(link, target);
            } catch (IOException | UnsupportedOperationException ex) {
                assumeTrue(false, (directory ? "Directory symlink" : "Symlink") + " creation unavailable: " + ex);
            }
        } finally {
            Files.deleteIfExists(link);
            Files.deleteIfExists(target);
            Files.delete(probe);
        }
    }
}
