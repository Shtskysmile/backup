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

/** End-to-end checks for the cloud backup flow: TLS, accounts, metadata, increments and retention. */
class NetworkBackupIntegrationTest {
    @TempDir Path temp;
    private NetworkBackupServer server;

    private NetworkBackupServer startServer() throws Exception {
        assumeTrue(keytoolAvailable(), "keytool is required to provision the server certificate");
        server = NetworkBackupServer.start(0, temp.resolve("storage"));
        return server;
    }

    private static boolean keytoolAvailable() {
        String name = System.getProperty("os.name", "").toLowerCase().contains("windows") ? "keytool.exe" : "keytool";
        return Files.isRegularFile(Path.of(System.getProperty("java.home"), "bin", name));
    }

    private NetworkBackupClient connect(NetworkBackupServer running, String username, char[] password) throws IOException {
        return new NetworkBackupClient("localhost", running.port(), username, password,
                running.storageRoot().resolve(NetworkBackupServer.KEYSTORE_FILE), NetworkBackupServer.KEYSTORE_PASSWORD);
    }

    @AfterEach void tearDown() {
        if (server != null) server.close();
    }

    private Path sampleSource(String name, String content) throws IOException {
        Path source = Files.createDirectories(temp.resolve(name));
        Files.writeString(source.resolve("notes.txt"), content);
        Files.createDirectories(source.resolve("sub"));
        Files.writeString(source.resolve("sub/data.bin"), content + content);
        return source;
    }

    private BackupOptions options(Path source, String name, boolean incremental, boolean encrypted, char[] passphrase) {
        return new BackupOptions(source, temp.resolve("unused-destination"), name, CompressionType.ZIP,
                incremental, encrypted, passphrase);
    }

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

    @Test void plainHttpIsRejectedBecauseTheServerSpeaksTlsOnly() throws Exception {
        NetworkBackupServer running = startServer();
        HttpClient plain = HttpClient.newBuilder().build();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + running.port() + "/api/ping")).GET().build();
        assertThrows(IOException.class, () -> plain.send(request, HttpResponse.BodyHandlers.ofString()));
    }

    @Test void fullThenIncrementalBackupRestoresAcrossServerChain() throws Exception {
        NetworkBackupServer running = startServer();
        NetworkUsers.ensureUser(running.storageRoot(), "alice", "secret".toCharArray());
        Path source = sampleSource("source", "one");
        NetworkBackupService service = new NetworkBackupService();
        try (NetworkBackupClient client = connect(running, "alice", "secret".toCharArray())) {
            NetworkBackupService.NetworkBackupResult full = service.backup(client, options(source, "proj", true, false, null), 0);
            assertTrue(full.full());
            assertTrue(full.archivedEntries() >= 3);
            Files.writeString(source.resolve("notes.txt"), "one-two");
            Files.writeString(source.resolve("extra.txt"), "new");
            NetworkBackupService.NetworkBackupResult increment = service.backup(client, options(source, "proj", true, false, null), 0);
            assertFalse(increment.full());
            // Directories always travel with an increment, like the local service.
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
            // A same-passphrase follow-up continues the encrypted chain incrementally.
            Files.writeString(source.resolve("notes.txt"), "classified-2");
            NetworkBackupService.NetworkBackupResult increment = service.backup(client,
                    options(source, "vault", true, true, "pass-phrase".toCharArray()), 0);
            assertFalse(increment.full());
        }
    }

    @Test void serverSideRetentionPrunesWholeOldGroups() throws Exception {
        NetworkBackupServer running = startServer();
        NetworkUsers.ensureUser(running.storageRoot(), "alice", "secret".toCharArray());
        Path source = sampleSource("rotate", "seed");
        NetworkBackupService service = new NetworkBackupService();
        try (NetworkBackupClient client = connect(running, "alice", "secret".toCharArray())) {
            for (int i = 0; i < 12; i++) {
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
                // The newest group is protected; deleting the older one must not hit that guard.
                fail("Older group delete should succeed: " + e.getMessage());
            }
            assertThrows(IOException.class, () -> client.deleteGroup(second.snapshot().groupId()));
            assertEquals(1, client.listSnapshots().size());
        }
    }

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
            Path stolen = temp.resolve("stolen");
            assertThrows(IOException.class, () -> bob.download("anything.zip", stolen));
        }
    }

    @Test void tamperedCommitPayloadIsRejected() throws Exception {
        NetworkBackupServer running = startServer();
        NetworkUsers.ensureUser(running.storageRoot(), "alice", "secret".toCharArray());
        Path source = sampleSource("audit", "integrity");
        NetworkBackupService service = new NetworkBackupService();
        try (NetworkBackupClient client = connect(running, "alice", "secret".toCharArray())) {
            NetworkBackupService.NetworkBackupResult result = service.backup(client, options(source, "audit", false, false, null), 0);
            // Unknown tokens and mismatched manifests must be refused.
            assertThrows(IOException.class, () -> client.commit("bogus-token", "", 0));
            Path userDir = running.storageRoot().resolve("users").resolve("alice");
            assertTrue(Files.exists(userDir.resolve(result.archiveName())));
            // A second commit for the same archive name cannot overwrite the stored snapshot.
            assertThrows(IOException.class, () -> {
                String token = client.upload(temp.resolve("notes.txt"));
                client.commit(token, "", 0);
            });
        }
    }
}
