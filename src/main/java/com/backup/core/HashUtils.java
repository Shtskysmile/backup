package com.backup.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class HashUtils {
    private HashUtils() {
    }

    /**
     * 计算文件 SHA-256：64KB 固定缓冲流式读取——内存占用与文件大小无关（GB 级文件安全）。
     * SHA-256 是增量判定与还原校验的统一指纹，碰撞概率在工程上可忽略。
     */
    public static String sha256(Path file) throws IOException {
        MessageDigest digest = newDigest();
        // 流式读取：64KB 缓冲，内存占用恒定；读完一次性 toHex。
        byte[] buffer = new byte[64 * 1024];
        try (InputStream inputStream = Files.newInputStream(file)) {
        // 循环读入并喂给摘要；没有一次性读入整个文件。
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return toHex(digest.digest());
    }

    /** 每次哈希新摘要实例（MessageDigest 非线程安全）。 */
    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /** 字节数组 → 小写十六进制（清单/比较统一形态）。 */
    private static String toHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            builder.append(Character.forDigit((b >>> 4) & 0xF, 16));
            builder.append(Character.forDigit(b & 0xF, 16));
        }
        return builder.toString();
    }
}
