package com.backup.network;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * 口令哈希工具：PBKDF2-HmacSHA256（210000 次迭代 / 256 位 / 16 字节随机盐）。
 *
 * <p>verify 用 MessageDigest.isEqual 做常数时间比较，杜绝时序侧信道；
 * hash/verify 的输入口令在 KeySpec 中用后立即 clearPassword。
 * 慢哈希的工程意义：把"每秒数十亿次猜测"压到"每秒几千次"，盐值防彩虹表。</p>
 */
public final class PasswordHashes {
    /** 迭代次数与 EncryptionService 保持同一量级（OWASP 推荐区间）。 */
    public static final int ITERATIONS = 210_000;
    public static final int SALT_BYTES = 16;
    public static final int KEY_BITS = 256;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordHashes() {}

    public record Verifier(String saltHex, String verifierHex, int iterations) {}

    public static Verifier hash(char[] password) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        return new Verifier(HexFormat.of().formatHex(salt), hex(password, salt, ITERATIONS), ITERATIONS);
    }

    public static boolean verify(char[] password, Verifier stored) {
        if (stored == null) return false;
        byte[] salt = HexFormat.of().parseHex(stored.saltHex());
        byte[] expected = HexFormat.of().parseHex(stored.verifierHex());
        // MessageDigest.isEqual：长度不同立即返回、长度相同常数时间——防时序逐位猜解。
        byte[] actual = bytes(password, salt, stored.iterations());
        try {
            return MessageDigest.isEqual(expected, actual);
        } finally {
            Arrays.fill(actual, (byte) 0);
        }
    }

    private static String hex(char[] password, byte[] salt, int iterations) {
        return HexFormat.of().formatHex(bytes(password, salt, iterations));
    }

    private static byte[] bytes(char[] password, byte[] salt, int iterations) {
        if (password == null || password.length == 0) throw new IllegalArgumentException("Password must not be empty");
        KeySpec spec = new PBEKeySpec(password, salt, iterations, KEY_BITS);
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] encoded = factory.generateSecret(spec).getEncoded();
            ((PBEKeySpec) spec).clearPassword();
            return encoded;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2 unavailable", e);
        }
    }
}
