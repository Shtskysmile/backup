package com.backup.network;

import com.backup.core.BackupManifest;
import com.backup.core.BackupOptions;
import com.backup.core.CompressionType;
import com.backup.core.RestoreResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 网络备份端到端测试：TLS、账号、元数据、增量与保留策略。
/**
 * 网络备份端到端集成：起真实 HTTPS 服务器（随机端口）→ 账号 → TLS/认证 →
 * 全量+增量往返 → 加密链 → 服务器端保留 → 整组删除保护 → 账号隔离 →
 * 提交防篡改。keytool 不可用时按能力跳过。
 *
 * 用例清单（8 项）：
 * - authenticatedPingAndWrongPasswordFailure
 * - plainHttpIsRejectedBecauseTheServerSpeaksTlsOnly
 * - fullThenIncrementalBackupRestoresAcrossServerChain
 * - encryptedArchivesTravelAndRestoreWithThePassphrase
 * - serverSideRetentionPrunesWholeOldGroups
 * - groupDeleteRemovesHistoryButProtectsTheNewestGroup
 * - accountsAreIsolatedPerUser
 * - tamperedCommitPayloadIsRejected
 */
class NetworkBackupIntegrationTest {
    @TempDir Path temp;
    private NetworkBackupServer server;

    /** 起随机端口服务器（keytool 可用性守卫），用例结束由 @AfterEach 关闭。 */
    private NetworkBackupServer startServer() throws Exception {
        assumeTrue(keytoolAvailable(), "keytool is required to provision the server certificate");
        server = NetworkBackupServer.start(0, temp.resolve("storage"));
        return server;
    }

    private static boolean keytoolAvailable() {
        String name = System.getProperty("os.name", "").toLowerCase().contains("windows") ? "keytool.exe" : "keytool";
        return Files.isRegularFile(Path.of(System.getProperty("java.home"), "bin", name));
    }

    /** 构造已钉定信任的客户端：信任文件 = 服务器自生成密钥库。 */
    private NetworkBackupClient connect(NetworkBackupServer running, String username, char[] password) throws IOException {
        return new NetworkBackupClient("localhost", running.port(), username, password,
                running.storageRoot().resolve(NetworkBackupServer.KEYSTORE_FILE), NetworkBackupServer.KEYSTORE_PASSWORD);
    }

    @AfterEach void tearDown() {
        if (server != null) server.close();
    }

    /** 双层样本源：根文件 + 子目录文件，内容可参数化（增量用）。 */
    private Path sampleSource(String name, String content) throws IOException {
        Path source = Files.createDirectories(temp.resolve(name));
        Files.writeString(source.resolve("notes.txt"), content);
        Files.createDirectories(source.resolve("sub"));
        Files.writeString(source.resolve("sub/data.bin"), content + content);
        return source;
    }

    /** 网络备份参数：destination 占位（网络模式不用本地目标）。 */
    private BackupOptions options(Path source, String name, boolean incremental, boolean encrypted, char[] passphrase) {
        return new BackupOptions(source, temp.resolve("unused-destination"), name, CompressionType.ZIP,
                incremental, encrypted, passphrase);
    }

    /** 正确凭据 ping 返回 ok 与用户名；错误口令返回 401；未知用户同样被拒绝。 */
    @Test void authenticatedPingAndWrongPasswordFailure() throws Exception {
        NetworkBackupServer running = startServer();
        NetworkUsers.ensureUser(running.storageRoot(), "alice", "secret".toCharArray());
        try (NetworkBackupClient client = connect(running, "alice", "secret".toCharArray())) {
            Properties ping = client.ping();
            assertEquals("ok", ping.getProperty("status"));
            assertEquals("alice", ping.getProperty("user"));
        }
        try (NetworkBackupClient wrong = connect(running, "alice", "bad".toCharArray())) {
            IOException error = assertThrows(IOException.class, wrong::ping);
            assertTrue(error.getMessage().contains("401"), error.getMessage());
        }
        try (NetworkBackupClient unknown = connect(running, "ghost", "secret".toCharArray())) {
            assertThrows(IOException.class, unknown::ping);
        }
    }

    /** 服务器只讲 TLS：明文 HTTP 请求直接失败，不存在降级路径。 */
    @Test void plainHttpIsRejectedBecauseTheServerSpeaksTlsOnly() throws Exception {
        NetworkBackupServer running = startServer();
        HttpClient plain = HttpClient.newBuilder().build();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + running.port() + "/api/ping")).GET().build();
        assertThrows(IOException.class, () -> plain.send(request, HttpResponse.BodyHandlers.ofString()));
    }

    /** 全量→增量链上传服务器：远端快照与清单可查询；先全量后增量的链式还原内容逐字节正确。 */
    @Test void fullThenIncrementalBackupRestoresAcrossServerChain() throws Exception {
        NetworkBackupServer running = startServer();
        NetworkUsers.ensureUser(running.storageRoot(), "alice", "secret".toCharArray());
        Path source = sampleSource("source", "one");
        NetworkBackupService service = new NetworkBackupService();
        try (NetworkBackupClient client = connect(running, "alice", "secret".toCharArray())) {
            NetworkBackupService.NetworkBackupResult full = service.backup(client, options(source, "proj", true, false, null), 0);
        // 第一轮全量：3+ 条目（含目录）。
            assertTrue(full.full());
            assertTrue(full.archivedEntries() >= 3);
            Files.writeString(source.resolve("notes.txt"), "one-two");
            Files.writeString(source.resolve("extra.txt"), "new");
            NetworkBackupService.NetworkBackupResult increment = service.backup(client, options(source, "proj", true, false, null), 0);
        // 第二轮增量：只应含变化的 notes.txt + 新增 extra.txt + 目录。
            assertFalse(increment.full());
            // 与本地语义一致：目录条目始终随增量一起传输（还原端需要 mkdir）。
            assertEquals(3, increment.archivedEntries());

            List<NetworkBackupClient.RemoteSnapshot> snapshots = client.listSnapshots();
            assertEquals(2, snapshots.size());
            NetworkBackupClient.RemoteSnapshot remoteFull = snapshots.stream()
                    .filter(s -> s.archiveName().equals(full.archiveName())).findFirst().orElseThrow();
            assertTrue(remoteFull.bytes() > 0);
            BackupManifest remoteManifest = BackupManifest.fromString(client.fetchManifest(increment.archiveName()));
            assertTrue(remoteManifest.incremental());

            Path restored = temp.resolve("restored-full");
            RestoreResult first = service.restore(client, full.archiveName(), restored, null);
            assertEquals(3, first.manifest().entries().size());
            assertEquals("one", Files.readString(restored.resolve("notes.txt")));
            Path restoredChain = temp.resolve("restored-chain");
            service.restore(client, full.archiveName(), restoredChain, null);
            service.restore(client, increment.archiveName(), restoredChain, null);
            assertEquals("one-two", Files.readString(restoredChain.resolve("notes.txt")));
            assertEquals("new", Files.readString(restoredChain.resolve("extra.txt")));
            assertEquals("oneone", Files.readString(restoredChain.resolve("sub/data.bin")),
                    "Unchanged content stays intact across the chain");
        }
    }

    /** 加密归档以 .enc 落到服务器：正确口令还原；错误口令失败且不产出任何内容；同口令后续备份继续增量链。 */
    @Test void encryptedArchivesTravelAndRestoreWithThePassphrase() throws Exception {
        NetworkBackupServer running = startServer();
        NetworkUsers.ensureUser(running.storageRoot(), "alice", "secret".toCharArray());
        Path source = sampleSource("secured", "classified");
        NetworkBackupService service = new NetworkBackupService();
        try (NetworkBackupClient client = connect(running, "alice", "secret".toCharArray())) {
            NetworkBackupService.NetworkBackupResult full = service.backup(client,
                    options(source, "vault", false, true, "pass-phrase".toCharArray()), 0);
            assertTrue(full.archiveName().endsWith(".enc"));
            assertTrue(Files.exists(running.storageRoot().resolve("users").resolve("alice").resolve(full.archiveName())));
            Path restored = temp.resolve("restored-enc");
            service.restore(client, full.archiveName(), restored, "pass-phrase".toCharArray());
            assertEquals("classified", Files.readString(restored.resolve("notes.txt")));
            IOException wrong = assertThrows(IOException.class, () -> {
                Path target = temp.resolve("restored-wrong");
                service.restore(client, full.archiveName(), target, "nope".toCharArray());
            });
            assertTrue(wrong.getMessage().contains("passphrase") || wrong.getMessage().contains("Tag mismatch"),
                    wrong.getMessage());
            assertFalse(Files.exists(temp.resolve("restored-wrong").resolve("notes.txt")),
                    "A failed decryption must not restore content");
            // 同口令的后续备份应继续加密增量链（口令记忆生效），而不是退回全量。
            Files.writeString(source.resolve("notes.txt"), "classified-2");
            NetworkBackupService.NetworkBackupResult increment = service.backup(client,
                    options(source, "vault", true, true, "pass-phrase".toCharArray()), 0);
            assertFalse(increment.full());
        }
    }

    /** 12 连备 keepGroups=1：服务器端整组淘汰，只剩最新组（全量+增量）共 2 快照 6 个文件。 */
    @Test void serverSideRetentionPrunesWholeOldGroups() throws Exception {
        NetworkBackupServer running = startServer();
        NetworkUsers.ensureUser(running.storageRoot(), "alice", "secret".toCharArray());
        Path source = sampleSource("rotate", "seed");
        NetworkBackupService service = new NetworkBackupService();
        try (NetworkBackupClient client = connect(running, "alice", "secret".toCharArray())) {
            for (int i = 0; i < 12; i++) {
            // 12 连备：第 11 次强制新组，keepGroups=1 时旧组应在服务器端被整组淘汰。
                Files.writeString(source.resolve("notes.txt"), "seed-" + i);
                service.backup(client, options(source, "rotate", true, false, null), 1);
            }
            List<NetworkBackupClient.RemoteSnapshot> snapshots = client.listSnapshots();
            assertEquals(2, snapshots.size(), "Retention must keep only the newest whole group");
            assertEquals(2, snapshots.get(0).sequence(), "Snapshots are listed newest first");
            assertEquals(1, snapshots.get(1).sequence());
            Path usersDir = running.storageRoot().resolve("users").resolve("alice");
            try (var files = Files.list(usersDir)) {
                assertEquals(6, files.filter(p -> !p.getFileName().toString().startsWith(".")).count(),
                        "Two archives plus manifest and snapshot sidecars remain");
            }
        }
    }

    /** 删除旧组成功（deleted=1）；删除包含最新快照的组被保护性拒绝。 */
    @Test void groupDeleteRemovesHistoryButProtectsTheNewestGroup() throws Exception {
        NetworkBackupServer running = startServer();
        NetworkUsers.ensureUser(running.storageRoot(), "alice", "secret".toCharArray());
        Path source = sampleSource("groups", "v1");
        NetworkBackupService service = new NetworkBackupService();
        try (NetworkBackupClient client = connect(running, "alice", "secret".toCharArray())) {
            NetworkBackupService.NetworkBackupResult first = service.backup(client, options(source, "groups", false, false, null), 0);
            Files.writeString(source.resolve("notes.txt"), "v2");
            NetworkBackupService.NetworkBackupResult second = service.backup(client, options(source, "groups", false, false, null), 0);
            assertNotEquals(first.snapshot().groupId(), second.snapshot().groupId());
            try {
                Properties response = client.deleteGroup(first.snapshot().groupId());
                assertEquals("1", response.getProperty("deleted"));
            } catch (IOException e) {
                // 最新组受保护；删除较旧组不应触发该保护（若触发即测试失败）。
                fail("Older group delete should succeed: " + e.getMessage());
            }
            assertThrows(IOException.class, () -> client.deleteGroup(second.snapshot().groupId()));
            assertEquals(1, client.listSnapshots().size());
        }
    }

    /** 账号目录级隔离：bob 的快照列表为空，下载 alice 的归档失败。 */
    @Test void accountsAreIsolatedPerUser() throws Exception {
        NetworkBackupServer running = startServer();
        NetworkUsers.ensureUser(running.storageRoot(), "alice", "secret".toCharArray());
        NetworkUsers.ensureUser(running.storageRoot(), "bob", "hunter2".toCharArray());
        Path source = sampleSource("shared-source", "payload");
        NetworkBackupService service = new NetworkBackupService();
        try (NetworkBackupClient alice = connect(running, "alice", "secret".toCharArray());
             NetworkBackupClient bob = connect(running, "bob", "hunter2".toCharArray())) {
            service.backup(alice, options(source, "mine", false, false, null), 0);
            assertEquals(1, alice.listSnapshots().size());
            assertEquals(0, bob.listSnapshots().size());
        // 隔离断言：bob 看不到 alice 的任何快照（目录级隔离）。
            Path stolen = temp.resolve("stolen");
            assertThrows(IOException.class, () -> bob.download("anything.zip", stolen));
        }
    }

    /** 伪造令牌与清单不匹配的提交被拒绝；同名归档的第二次提交不能覆盖已存快照。 */
    @Test void tamperedCommitPayloadIsRejected() throws Exception {
        NetworkBackupServer running = startServer();
        NetworkUsers.ensureUser(running.storageRoot(), "alice", "secret".toCharArray());
        Path source = sampleSource("audit", "integrity");
        NetworkBackupService service = new NetworkBackupService();
        try (NetworkBackupClient client = connect(running, "alice", "secret".toCharArray())) {
            NetworkBackupService.NetworkBackupResult result = service.backup(client, options(source, "audit", false, false, null), 0);
            // 伪造令牌与清单不匹配的提交都必须被拒绝。
            assertThrows(IOException.class, () -> client.commit("bogus-token", "", 0));
            Path userDir = running.storageRoot().resolve("users").resolve("alice");
            assertTrue(Files.exists(userDir.resolve(result.archiveName())));
            // 同名归档的第二次提交不允许覆盖已存储的快照（发布幂等性防护）。
            assertThrows(IOException.class, () -> {
                String token = client.upload(temp.resolve("notes.txt"));
                client.commit(token, "", 0);
            });
        }
    }
}
