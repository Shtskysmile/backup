package com.backup.network;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Server-side account store. Accounts live in a single properties file guarded by atomic moves;
 * only salted PBKDF2 verifiers are persisted, never the passwords themselves.
 */
/**
 * 服务器端账号存储：单文件 users.properties，仅持久化 PBKDF2 验证值（明文口令永不落盘）。
 *
 * <p>安全设计：① 用户名白名单 [A-Za-z0-9][A-Za-z0-9._-]{0,31} 且映射为
 * users/<账号>/ 隔离目录——目录穿越在用户名层面被结构上排除；② 写入用
 * 临时文件 + ATOMIC_MOVE；③ authenticate 对不存在的用户同样执行一次完整推导
 * （恒定时间响应），消除"用户枚举"时序侧信道；④ 密码修改必须先通过旧口令验证。</p>
 */
public final class NetworkUsers {
    public static final String FILE_NAME = "users.properties";
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,31}");
    private static final Set<String> RESERVED = Set.of("users", "server-keystore.p12");

    private NetworkUsers() {}

    /** 用户名白名单 + 保留名检查（users/server-keystore.p12 不可作为账号）。 */
    public static void validateUsername(String username) {
        if (username == null || !USERNAME.matcher(username).matches() || RESERVED.contains(username.toLowerCase())) {
            throw new IllegalArgumentException("Invalid username: use 1-32 letters, digits, dots, dashes or underscores");
        }
    }

    /** 账号 → 隔离目录：users/<账号>/，白名单保证路径拼接安全。 */
    public static Path userDirectory(Path storageRoot, String username) {
        validateUsername(username);
        return storageRoot.resolve("users").resolve(username);
    }

    /** 创建账号：已存在抛出；写完账号文件再建用户目录（顺序保证认证可用）。 */
    public static synchronized void ensureUser(Path storageRoot, String username, char[] password) throws IOException {
        validateUsername(username);
        Objects.requireNonNull(password);
        if (password.length == 0) throw new IllegalArgumentException("Password must not be empty");
        Path file = storeFile(storageRoot);
        Properties users = load(file);
        if (users.containsKey(username + ".verifier")) {
        // 幂等约束：已存在的账号拒绝重复创建（改名不改口令语义）。
            throw new IOException("User already exists: " + username);
        }
        PasswordHashes.Verifier verifier = PasswordHashes.hash(password);
        // 每账号独立随机盐：同口令不同用户验证值也不同（防彩虹表/撞库比对）。
        users.setProperty(username + ".salt", verifier.saltHex());
        users.setProperty(username + ".verifier", verifier.verifierHex());
        users.setProperty(username + ".iterations", Integer.toString(verifier.iterations()));
        storeAtomically(file, users);
        Files.createDirectories(userDirectory(storageRoot, username));
    }

    /** Constant-time credential check; unknown users still cost one verification. */
    public static synchronized boolean authenticate(Path storageRoot, String username, char[] password) throws IOException {
        if (username == null || password == null || password.length == 0) return false;
        Properties users = load(storeFile(storageRoot));
        // 认证读取：未知用户走等时假验证（防时序用户枚举），已知用户常数时间比较。
        String salt = users.getProperty(username + ".salt");
        String verifier = users.getProperty(username + ".verifier");
        String iterations = users.getProperty(username + ".iterations");
        if (salt == null || verifier == null || iterations == null) {
            PasswordHashes.verify(password, PasswordHashes.hash("timing-equalizer".toCharArray()));
            return false;
        }
        return PasswordHashes.verify(password, new PasswordHashes.Verifier(salt, verifier, Integer.parseInt(iterations)));
    }

    /** 改密：先以旧口令走完整认证（防越权改密），再重置盐值与验证值。 */
    public static synchronized void changePassword(Path storageRoot, String username, char[] oldPassword, char[] newPassword) throws IOException {
        validateUsername(username);
        if (!authenticate(storageRoot, username, oldPassword)) {
            throw new IOException("Authentication failed for " + username);
        }
        if (newPassword == null || newPassword.length == 0) throw new IllegalArgumentException("Password must not be empty");
        Path file = storeFile(storageRoot);
        Properties users = load(file);
        PasswordHashes.Verifier verifier = PasswordHashes.hash(newPassword);
        users.setProperty(username + ".salt", verifier.saltHex());
        users.setProperty(username + ".verifier", verifier.verifierHex());
        users.setProperty(username + ".iterations", Integer.toString(verifier.iterations()));
        storeAtomically(file, users);
    }

    /** 账号文件定位 + 根目录创建。 */
    private static Path storeFile(Path storageRoot) throws IOException {
        Files.createDirectories(storageRoot);
        return storageRoot.resolve(FILE_NAME);
    }

    /** 读取账号表（文件不存在视为空表，首次启动即用）。 */
    private static Properties load(Path file) throws IOException {
        Properties properties = new Properties();
        if (Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
        }
        return properties;
    }

    /** 账号表原子写：并发创建账号也不会出现半份文件。 */
    private static void storeAtomically(Path file, Properties properties) throws IOException {
        Path temporary = Files.createTempFile(file.getParent(), ".users-", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                properties.store(writer, "Backup network accounts");
            }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
