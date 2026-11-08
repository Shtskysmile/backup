package com.backup.io;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 软链接自身权限的安全恢复：JDK 的权限 setter 在部分平台会跟随链接改到目标
 * （历史上有安全风险），因此改用 BSD chmod -h（lchmod 语义）只作用于链接本身。
 *
 * <p>仅 macOS 支持修改链接权限（mutableLinkModes）；其它平台抛
 * UnsupportedOperationException，由上层转为警告。流程：先比对当前权限，
 * 一致则不动；执行 chmod -h 带 5 秒超时，输出重定向到日志文件供失败诊断。</p>
 */
final class SymbolicLinkPermissions {
    private final String chmod;
    private final boolean mutableLinkModes;

    /** 默认：/bin/chmod；仅 macOS 认为链接权限可变（其余平台直接降级警告）。 */
    SymbolicLinkPermissions() {
        this("/bin/chmod", System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac"));
    }
    SymbolicLinkPermissions(String chmod, boolean mutableLinkModes) {
        this.chmod = chmod; this.mutableLinkModes = mutableLinkModes;
    }

    void restore(Path link, Set<PosixFilePermission> permissions) throws IOException {
        requireLink(link);
        if (Files.getPosixFilePermissions(link, LinkOption.NOFOLLOW_LINKS).equals(permissions)) return;
        if (!mutableLinkModes) throw new UnsupportedOperationException("Changing symbolic-link permissions is not supported on this platform");
        int mode = 0;
        // 权限集 → 八进制：BSD chmod -h 只改链接自身（lchmod 语义，绝不跟随）。
        for (PosixFilePermission permission : permissions) mode |= switch (permission) {
            case OWNER_READ -> 0400; case OWNER_WRITE -> 0200; case OWNER_EXECUTE -> 0100;
            case GROUP_READ -> 0040; case GROUP_WRITE -> 0020; case GROUP_EXECUTE -> 0010;
            case OTHERS_READ -> 0004; case OTHERS_WRITE -> 0002; case OTHERS_EXECUTE -> 0001;
        };
        Path log = Files.createTempFile("backup-link-permissions-", ".log");
        // 工具输出落临时日志：失败时消息里带回摘要，便于诊断。
        Process process = null;
        try {
            // BSD chmod -h 即 lchmod：断链/绝对路径/指向目录的链接都绝不会被解引用。
            process = new ProcessBuilder(chmod, "-h", Integer.toOctalString(mode), link.toAbsolutePath().toString())
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            if (!process.waitFor(5, TimeUnit.SECONDS)) throw new IOException("Symbolic-link chmod timed out after 5 seconds");
        // 5 秒超时：外部工具永不挂死还原流程。
            if (process.exitValue() != 0) {
                byte[] bytes;
                try (var in = Files.newInputStream(log)) { bytes = in.readNBytes(4096); }
                throw new IOException("Symbolic-link chmod exited " + process.exitValue() + ": " + new String(bytes, StandardCharsets.UTF_8).strip());
            }
            requireLink(link);
            if (!Files.getPosixFilePermissions(link, LinkOption.NOFOLLOW_LINKS).equals(permissions)) {
                throw new IOException("Symbolic-link permissions differ from the manifest after chmod");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new IOException("Symbolic-link chmod interrupted", e);
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            Files.deleteIfExists(log);
        }
    }

    /** 前置校验：目标必须是符号链接本身（NOFOLLOW），防止误改普通文件权限。 */
    private static void requireLink(Path link) throws IOException {
        if (!Files.isSymbolicLink(link)) throw new IOException("Expected a symbolic link for permission restoration: " + link);
    }
}
