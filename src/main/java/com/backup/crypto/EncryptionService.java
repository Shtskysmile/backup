package com.backup.crypto;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Arrays;

public final class EncryptionService {
    private static final byte[] MAGIC = "BPACKENC1".getBytes(StandardCharsets.US_ASCII);
    private static final int PBKDF2_ITERATIONS = 210_000;
    private static final int KEY_BITS = 256;
    private static final int SALT_BYTES = 16;
    private static final int IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;

    private final SecureRandom secureRandom = new SecureRandom();

    public boolean isEncrypted(Path file) throws IOException {
        if (!Files.isRegularFile(file) || Files.size(file) < MAGIC.length) {
            return false;
        }
        byte[] header = new byte[MAGIC.length];
        try (InputStream inputStream = Files.newInputStream(file)) {
            int read = inputStream.read(header);
            return read == MAGIC.length && Arrays.equals(MAGIC, header);
        }
    }

    public void encrypt(Path input, Path output, char[] passphrase) throws IOException {
        byte[] salt = randomBytes(SALT_BYTES);
        byte[] iv = randomBytes(IV_BYTES);
        try {
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, passphrase, salt, iv);
            try (OutputStream fileOut = Files.newOutputStream(output);
                 DataOutputStream headerOut = new DataOutputStream(new BufferedOutputStream(fileOut))) {
                headerOut.write(MAGIC);
                headerOut.writeInt(salt.length);
                headerOut.write(salt);
                headerOut.writeInt(iv.length);
                headerOut.write(iv);
                headerOut.flush();
                try (CipherOutputStream cipherOut = new CipherOutputStream(fileOut, cipher)) {
                    Files.copy(input, cipherOut);
                }
            }
        } catch (GeneralSecurityException e) {
            throw new IOException("Unable to encrypt backup", e);
        }
    }

    public Path decryptToTempFile(Path input, char[] passphrase) throws IOException {
        Path tempFile = Files.createTempFile("backup-decrypted-", ".tmp");
        try {
            decrypt(input, tempFile, passphrase);
            return tempFile;
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(tempFile);
            throw e;
        }
    }

    public void decrypt(Path input, Path output, char[] passphrase) throws IOException {
        try (DataInputStream headerIn = new DataInputStream(new BufferedInputStream(Files.newInputStream(input)))) {
            byte[] magic = headerIn.readNBytes(MAGIC.length);
            if (!Arrays.equals(MAGIC, magic)) {
                throw new IOException("File is not a Backup encrypted archive.");
            }
            byte[] salt = readBoundedBytes(headerIn, 64, "salt");
            byte[] iv = readBoundedBytes(headerIn, 32, "IV");
            Cipher cipher = cipher(Cipher.DECRYPT_MODE, passphrase, salt, iv);
            try (CipherInputStream cipherIn = new CipherInputStream(headerIn, cipher);
                 OutputStream outputStream = Files.newOutputStream(output)) {
                cipherIn.transferTo(outputStream);
            } catch (IOException streamFailure) {
                // CipherInputStream reports authentication failures as plain IOExceptions.
                if (streamFailure.getCause() instanceof GeneralSecurityException
                        || streamFailure.getMessage() != null && streamFailure.getMessage().contains("Tag mismatch")) {
                    throw new IOException("Unable to decrypt backup. Check the passphrase.", streamFailure);
                }
                throw streamFailure;
            }
        } catch (GeneralSecurityException e) {
            throw new IOException("Unable to decrypt backup. Check the passphrase.", e);
        }
    }

    private byte[] readBoundedBytes(DataInputStream inputStream, int max, String label) throws IOException {
        int length = inputStream.readInt();
        if (length <= 0 || length > max) {
            throw new IOException("Invalid encrypted archive " + label + " length: " + length);
        }
        byte[] bytes = inputStream.readNBytes(length);
        if (bytes.length != length) {
            throw new IOException("Unexpected end of encrypted archive while reading " + label + ".");
        }
        return bytes;
    }

    private Cipher cipher(int mode, char[] passphrase, byte[] salt, byte[] iv) throws GeneralSecurityException {
        SecretKeySpec key = deriveKey(passphrase, salt);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
        return cipher;
    }

    private SecretKeySpec deriveKey(char[] passphrase, byte[] salt) throws GeneralSecurityException {
        KeySpec spec = new PBEKeySpec(passphrase, salt, PBKDF2_ITERATIONS, KEY_BITS);
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] encoded = factory.generateSecret(spec).getEncoded();
            try {
                return new SecretKeySpec(encoded, "AES");
            } finally {
                Arrays.fill(encoded, (byte) 0);
            }
        } finally {
            ((PBEKeySpec) spec).clearPassword();
        }
    }

    private byte[] randomBytes(int size) {
        byte[] bytes = new byte[size];
        secureRandom.nextBytes(bytes);
        return bytes;
    }
}
