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

/**
 * 归档加密解密：AES-256-GCM 认证加密 + PBKDF2-HmacSHA256 口令派生（210000 次迭代）。
 *
 * <p>文件格式：魔数 "BPACKENC1" + 长度前缀盐(16B) + 长度前缀 IV(12B) + GCM 密流（尾部 128 位认证标签）。
 * 认证加密同时提供机密性与完整性：口令错误或密文被篡改一位，解密都会失败——
 * 失败必须干净（不产出坏数据），因此解密写到独立临时文件，成功才交给调用方。</p>
 *
 * <p>安全细节：盐与 IV 每次加密重新随机（GCM 的 IV 绝不可复用）；派生出的密钥字节用后
 * 立即清零；PBEKeySpec 在 finally 中 clearPassword；迭代次数取 OWASP 推荐量级，
 * 把暴力穷举成本抬高约六个数量级。</p>
 *
 * <p>已知坑（已修复）：JDK CipherInputStream 把 GCM 认证失败包装成普通 IOException
 * 抛出，不会被 GeneralSecurityException 捕获——decrypt 内部对 "Tag mismatch" 做了识别
 * 并转成明确的口令错误提示。</p>
 */
public final class EncryptionService {
    /** 归档魔数：isEncrypted 只读 9 字节即可判断，全量解密前先做廉价预检。 */
    private static final byte[] MAGIC = "BPACKENC1".getBytes(StandardCharsets.US_ASCII);
    private static final int PBKDF2_ITERATIONS = 210_000;
    private static final int KEY_BITS = 256;
    private static final int SALT_BYTES = 16;
    private static final int IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;

    private final SecureRandom secureRandom = new SecureRandom();

    /** 廉价预检：读魔数判断归档是否加密（还原入口据此决定是否要求口令）。 */
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

    /** 加密：随机盐/IV → 派生密钥 → 头部（魔数+盐+IV） → GCM 流式加密。失败抛 IOException 且输出不完整。 */
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

    /** 解密到临时文件：失败时自动清理临时文件后把异常抛给调用方——保证不留半份明文。 */
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

    /** 解密核心：头校验 → GCM 流式解密；认证失败转口令错误提示（见类注释）。 */
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
            // 流式解密：tag 在流末尾校验——读到最后一字节才知道口令/完整性是否通过。
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

    /** 长度前缀字节块读取（盐/IV）：超界长度立即拒绝，防头部伪造。 */
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

    /** 派生密钥：PBKDF2(口令, 盐, 210000, 256bit) → AES 密钥；密钥字节与口令数组用后即清。 */
    /** 组装 Cipher：PBKDF2 派生 256 位密钥 → AES/GCM/NoPadding（128 位认证标签）。 */
    private Cipher cipher(int mode, char[] passphrase, byte[] salt, byte[] iv) throws GeneralSecurityException {
        SecretKeySpec key = deriveKey(passphrase, salt);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
        return cipher;
    }

    /** PBKDF2 推导；密钥字节与口令在 finally 中清零，缩短敏感数据驻留。 */
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

    /** 加密学安全随机（盐/IV 的唯一来源）。 */
    private byte[] randomBytes(int size) {
        byte[] bytes = new byte[size];
        secureRandom.nextBytes(bytes);
        return bytes;
    }
}
