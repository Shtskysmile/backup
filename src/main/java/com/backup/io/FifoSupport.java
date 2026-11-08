package com.backup.io;

import com.backup.core.BackupEntry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * POSIX 管道（FIFO）支持：核心不变量是【任何代码路径都不打开管道的数据流】——
 * 打开读端会阻塞到有写者出现，备份/还原若误读管道即永久挂死。
 *
 * <p>创建：mkfifo -m 八进制权限（权限从清单的 POSIX 串换算，缺省 0600），
 * 创建后用属性位复核"真的建出了 FIFO"（防 mkfifo 假成功）；注入式构造器允许测试
 * 替换 mkfifo/touch 以模拟失败、超时与假成功路径。</p>
 */
final class FifoSupport {
    private static final DateTimeFormatter TOUCH_TIME = DateTimeFormatter.ofPattern("uuuuMMddHHmm.ss").withZone(ZoneOffset.UTC);
    private final String mkfifo;
    private final String touch;

    /** 默认工具：PATH 中的 mkfifo/touch；注入构造器供测试替换。 */
    FifoSupport() { this("mkfifo", "touch"); }
    FifoSupport(String mkfifo, String touch) { this.mkfifo = mkfifo; this.touch = touch; }

    /** 判断路径是否为 FIFO（读 unix:mode 类型位；不支持的平台安静返回 false）。 */
    static boolean isFifo(Path path) {
        try {
            Object mode = Files.getAttribute(path, "unix:mode", LinkOption.NOFOLLOW_LINKS);
            return mode instanceof Integer bits && (bits & 0170000) == 0010000;
        } catch (IOException | UnsupportedOperationException | IllegalArgumentException e) { return false; }
    }

    /** 在临时路径创建 FIFO：权限取自清单（缺省 0600），创建后用类型位复核防假成功。 */
    void create(BackupEntry entry, Path staged) throws IOException {
        int mode = 0600;
        // 权限换算：清单里的 POSIX 串 → mkfifo -m 八进制；缺省 0600。
        if (!entry.permissions().isBlank()) {
            mode = 0;
            for (PosixFilePermission permission : PosixFilePermissions.fromString(entry.permissions())) {
                mode |= switch (permission) {
                    case OWNER_READ -> 0400; case OWNER_WRITE -> 0200; case OWNER_EXECUTE -> 0100;
                    case GROUP_READ -> 0040; case GROUP_WRITE -> 0020; case GROUP_EXECUTE -> 0010;
                    case OTHERS_READ -> 0004; case OTHERS_WRITE -> 0002; case OTHERS_EXECUTE -> 0001;
                };
            }
        }
        run(List.of(mkfifo, "-m", Integer.toOctalString(mode), staged.toString()));
        // mkfifo -m 一次建出正确权限的管道节点（之后不再 chmod，减少工具依赖）。
        if (!isFifo(staged)) throw new IOException("mkfifo did not create a FIFO");
        // 假成功防护：exit 0 不代表节点类型正确，必须用类型位复核。
    }

    void restoreTimes(BackupEntry entry, Path path, Consumer<String> warnings) {
        restoreTime(entry, path, "-m", entry.lastModifiedMillis(), "modified time", warnings);
        if (entry.lastAccessMillis() != null) restoreTime(entry, path, "-a", entry.lastAccessMillis(), "access time", warnings);
        if (entry.creationMillis() != null) warnings.accept(entry.relativePath() + ": FIFO creation time is not restored on this platform");
    }

    /** 用 touch -c -h 恢复管道时间：不打开流；工具缺失/失败由调用方转为警告。 */
    private void restoreTime(BackupEntry entry, Path path, String flag, long millis, String name, Consumer<String> warnings) {
        try {
            // -h 不跟随（可能已被换名的）符号链接；UTC 时间避免本地夏令时的歧义。
            run(List.of(touch, "-c", "-h", flag, "-t", TOUCH_TIME.format(Instant.ofEpochMilli(millis)), path.toString()));
            if (Math.floorMod(millis, 1000) != 0) warnings.accept(entry.relativePath() + ": FIFO " + name + " restored with whole-second precision");
        } catch (IOException | RuntimeException e) {
            warnings.accept(entry.relativePath() + ": could not restore FIFO " + name + " (" + e.getMessage() + ")");
        }
    }

    /** 外部工具执行：收集输出 + 超时保护，非零退出抛 IOException。 */
    private void run(List<String> command) throws IOException {
        Path log = Files.createTempFile("backup-fifo-command-", ".log");
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
            builder.environment().put("TZ", "UTC0");
            process = builder.start();
            if (!process.waitFor(5, TimeUnit.SECONDS)) throw new IOException(command.getFirst() + " timed out after 5 seconds");
            if (process.exitValue() != 0) {
                byte[] bytes;
                try (var in = Files.newInputStream(log)) { bytes = in.readNBytes(4096); }
                throw new IOException(command.getFirst() + " exited " + process.exitValue() + ": " + new String(bytes, StandardCharsets.UTF_8).strip());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(command.getFirst() + " interrupted", e);
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            Files.deleteIfExists(log);
        }
    }
}
