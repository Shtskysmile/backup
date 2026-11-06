package com.backup.network;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Arrays;
import java.util.HexFormat;

/** Salted PBKDF2 password hashing shared by the network user store. */
public final class PasswordHashes {
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
