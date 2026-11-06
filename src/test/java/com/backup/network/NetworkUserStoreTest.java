package com.backup.network;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 账号存储测试：PBKDF2 验证值持久化（无明文）、认证与拒绝路径、
 * 非法用户名/空口令、密码修改需旧口令、跨实例加载。
 *
 * 用例清单（4 项）：
 * - createdUserAuthenticatesAndPersistsAcrossInstances
 * - storedRecordsAreSaltedVerifiersNotPasswords
 * - duplicateAndInvalidUsernamesAreRejected
 * - changePasswordVerifiesTheOldPassword
 */
class NetworkUserStoreTest {
    @TempDir Path root;

    /** 用例 createdUserAuthenticatesAndPersistsAcrossInstances：网络账号存储 的具体场景，断言即预期行为。 */
    @Test void createdUserAuthenticatesAndPersistsAcrossInstances() throws Exception {
        NetworkUsers.ensureUser(root, "alice", "wonder^secret".toCharArray());
        assertTrue(NetworkUsers.authenticate(root, "alice", "wonder^secret".toCharArray()));
        assertFalse(NetworkUsers.authenticate(root, "alice", "wrong".toCharArray()));
        assertFalse(NetworkUsers.authenticate(root, "mallory", "wonder^secret".toCharArray()));
        assertFalse(NetworkUsers.authenticate(root, "alice", "".toCharArray()));
        assertTrue(Files.exists(root.resolve(NetworkUsers.FILE_NAME)));
        Path other = root.resolve("copy-" + System.nanoTime());
        Files.createDirectories(other);
        Files.copy(root.resolve(NetworkUsers.FILE_NAME), other.resolve(NetworkUsers.FILE_NAME));
        assertTrue(NetworkUsers.authenticate(other, "alice", "wonder^secret".toCharArray()));
    }

    /** 用例 storedRecordsAreSaltedVerifiersNotPasswords：网络账号存储 的具体场景，断言即预期行为。 */
    @Test void storedRecordsAreSaltedVerifiersNotPasswords() throws Exception {
        NetworkUsers.ensureUser(root, "bob", "builder".toCharArray());
        String stored = Files.readString(root.resolve(NetworkUsers.FILE_NAME));
        assertFalse(stored.contains("builder"), "Plaintext passwords must never be persisted");
        assertTrue(stored.contains("bob.verifier="));
        assertTrue(stored.contains("bob.salt="));
    }

    /** 用例 duplicateAndInvalidUsernamesAreRejected：网络账号存储 的具体场景，断言即预期行为。 */
    @Test void duplicateAndInvalidUsernamesAreRejected() throws Exception {
        NetworkUsers.ensureUser(root, "carol", "pass-1".toCharArray());
        assertThrows(IOException.class, () -> NetworkUsers.ensureUser(root, "carol", "pass-2".toCharArray()));
        assertThrows(IllegalArgumentException.class, () -> NetworkUsers.ensureUser(root, "../eve", "pass".toCharArray()));
        assertThrows(IllegalArgumentException.class, () -> NetworkUsers.ensureUser(root, "a b", "pass".toCharArray()));
        assertThrows(IllegalArgumentException.class, () -> NetworkUsers.ensureUser(root, "", "pass".toCharArray()));
        assertThrows(IllegalArgumentException.class, () -> NetworkUsers.ensureUser(root, "dave", new char[0]));
        assertFalse(NetworkUsers.authenticate(root, "../eve", "pass".toCharArray()));
    }

    /** 用例 changePasswordVerifiesTheOldPassword：网络账号存储 的具体场景，断言即预期行为。 */
    @Test void changePasswordVerifiesTheOldPassword() throws Exception {
        NetworkUsers.ensureUser(root, "erin", "old-pass".toCharArray());
        assertThrows(IOException.class, () -> NetworkUsers.changePassword(root, "erin", "nope".toCharArray(), "new-pass".toCharArray()));
        NetworkUsers.changePassword(root, "erin", "old-pass".toCharArray(), "new-pass".toCharArray());
        assertTrue(NetworkUsers.authenticate(root, "erin", "new-pass".toCharArray()));
        assertFalse(NetworkUsers.authenticate(root, "erin", "old-pass".toCharArray()));
    }
}
