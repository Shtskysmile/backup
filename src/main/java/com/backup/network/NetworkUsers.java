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
public final class NetworkUsers {
    public static final String FILE_NAME = "users.properties";
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,31}");
    private static final Set<String> RESERVED = Set.of("users", "server-keystore.p12");

    private NetworkUsers() {}

    public static void validateUsername(String username) {
        if (username == null || !USERNAME.matcher(username).matches() || RESERVED.contains(username.toLowerCase())) {
            throw new IllegalArgumentException("Invalid username: use 1-32 letters, digits, dots, dashes or underscores");
        }
    }

    public static Path userDirectory(Path storageRoot, String username) {
        validateUsername(username);
        return storageRoot.resolve("users").resolve(username);
    }

    public static synchronized void ensureUser(Path storageRoot, String username, char[] password) throws IOException {
        validateUsername(username);
        Objects.requireNonNull(password);
        if (password.length == 0) throw new IllegalArgumentException("Password must not be empty");
        Path file = storeFile(storageRoot);
        Properties users = load(file);
        if (users.containsKey(username + ".verifier")) {
            throw new IOException("User already exists: " + username);
        }
        PasswordHashes.Verifier verifier = PasswordHashes.hash(password);
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
        String salt = users.getProperty(username + ".salt");
        String verifier = users.getProperty(username + ".verifier");
        String iterations = users.getProperty(username + ".iterations");
        if (salt == null || verifier == null || iterations == null) {
            PasswordHashes.verify(password, PasswordHashes.hash("timing-equalizer".toCharArray()));
            return false;
        }
        return PasswordHashes.verify(password, new PasswordHashes.Verifier(salt, verifier, Integer.parseInt(iterations)));
    }

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

    private static Path storeFile(Path storageRoot) throws IOException {
        Files.createDirectories(storageRoot);
        return storageRoot.resolve(FILE_NAME);
    }

    private static Properties load(Path file) throws IOException {
        Properties properties = new Properties();
        if (Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
        }
        return properties;
    }

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
