package com.backup.network;

import com.backup.core.BackupCatalog;
import com.backup.core.BackupManifest;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyStore;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * HTTPS backup receiver. Users authenticate with Basic credentials against the PBKDF2 account
 * store; every account maps to an isolated directory that mirrors the local backup destination
 * layout (archives, manifest sidecars, snapshot catalog and compatibility latest manifest).
 * Request and response bodies other than raw archive payloads are java.util.Properties text.
 */
public final class NetworkBackupServer implements AutoCloseable {
    public static final String KEYSTORE_FILE = "server-keystore.p12";
    public static final char[] KEYSTORE_PASSWORD = "backup-server-identity".toCharArray();
    private static final int MAX_REQUEST_BODY = 64 * 1024 * 1024;
    private static final long UPLOAD_TTL_MILLIS = 60 * 60 * 1000;

    private final HttpsServer server;
    private final ExecutorService executorService;
    private final Path storageRoot;
    private final Map<String, StagedUpload> staged = new ConcurrentHashMap<>();

    private NetworkBackupServer(HttpsServer server, ExecutorService executorService, Path storageRoot) {
        this.server = server;
        this.executorService = executorService;
        this.storageRoot = storageRoot;
    }

    public static NetworkBackupServer start(int port, Path storageRoot) throws IOException {
        Objects.requireNonNull(storageRoot);
        Files.createDirectories(storageRoot);
        cleanupStaleUploads(storageRoot);
        Path keystore = storageRoot.resolve(KEYSTORE_FILE);
        ensureKeystore(keystore);
        SSLContext sslContext = sslContext(keystore);
        HttpsServer httpsServer = HttpsServer.create(new InetSocketAddress(port), 0);
        httpsServer.setHttpsConfigurator(new HttpsConfigurator(sslContext));
        ExecutorService executorService = Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r, "backup-network-server");
            thread.setDaemon(true);
            return thread;
        });
        NetworkBackupServer networkServer = new NetworkBackupServer(httpsServer, executorService, storageRoot.toAbsolutePath().normalize());
        httpsServer.createContext("/api/ping", networkServer.authenticated(networkServer::handlePing));
        httpsServer.createContext("/api/upload", networkServer.authenticated(networkServer::handleUpload));
        httpsServer.createContext("/api/commit", networkServer.authenticated(networkServer::handleCommit));
        httpsServer.createContext("/api/snapshots", networkServer.authenticated(networkServer::handleSnapshots));
        httpsServer.createContext("/api/manifest", networkServer.authenticated(networkServer::handleManifest));
        httpsServer.createContext("/api/download", networkServer.authenticated(networkServer::handleDownload));
        httpsServer.createContext("/api/delete", networkServer.authenticated(networkServer::handleDelete));
        httpsServer.setExecutor(executorService);
        httpsServer.start();
        return networkServer;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public Path storageRoot() {
        return storageRoot;
    }

    @Override public void close() {
        server.stop(0);
        executorService.shutdownNow();
        for (StagedUpload upload : staged.values()) upload.delete();
        staged.clear();
    }

    private interface AuthedHandler {
        void handle(HttpExchange exchange, String username) throws IOException;
    }

    private com.sun.net.httpserver.HttpHandler authenticated(AuthedHandler handler) {
        return exchange -> {
            String username = authenticate(exchange);
            if (username == null) {
                exchange.getResponseHeaders().set("WWW-Authenticate", "Basic realm=\"backup\"");
                sendError(exchange, 401, "Authentication required");
                return;
            }
            try {
                handler.handle(exchange, username);
            } catch (BadRequestException e) {
                sendError(exchange, 400, e.getMessage());
            } catch (FileNotFoundException | NoSuchFileException e) {
                sendError(exchange, 404, "Not found: " + e.getMessage());
            } catch (IOException | RuntimeException e) {
                sendError(exchange, 500, String.valueOf(e.getMessage()));
            } finally {
                exchange.close();
            }
        };
    }

    private String authenticate(HttpExchange exchange) throws IOException {
        List<String> headers = exchange.getRequestHeaders().get("Authorization");
        if (headers == null || headers.isEmpty()) return null;
        String header = headers.getFirst();
        if (!header.regionMatches(true, 0, "Basic ", 0, 6)) return null;
        String decoded;
        try {
            decoded = new String(Base64.getDecoder().decode(header.substring(6).trim()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
        int separator = decoded.indexOf(':');
        if (separator <= 0) return null;
        String username = decoded.substring(0, separator);
        char[] password = decoded.substring(separator + 1).toCharArray();
        try {
            if (!NetworkUsers.authenticate(storageRoot, username, password)) return null;
        } catch (IOException e) {
            return null;
        } finally {
            Arrays.fill(password, '\0');
        }
        return username;
    }

    private void handlePing(HttpExchange exchange, String username) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "GET only");
            return;
        }
        Properties response = new Properties();
        response.setProperty("status", "ok");
        response.setProperty("user", username);
        sendProperties(exchange, 200, response);
    }

    private void handleUpload(HttpExchange exchange, String username) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "POST only");
            return;
        }
        Path userDirectory = NetworkUsers.userDirectory(storageRoot, username);
        Files.createDirectories(userDirectory);
        Path stagedFile = Files.createTempFile(userDirectory, ".incoming-", ".part");
        String token = UUID.randomUUID().toString().replace("-", "");
        try (InputStream body = exchange.getRequestBody()) {
            Files.copy(body, stagedFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.deleteIfExists(stagedFile);
            throw e;
        }
        staged.put(token, new StagedUpload(stagedFile, System.currentTimeMillis()));
        Properties response = new Properties();
        response.setProperty("token", token);
        response.setProperty("bytes", Long.toString(Files.size(stagedFile)));
        sendProperties(exchange, 200, response);
    }

    private void handleCommit(HttpExchange exchange, String username) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "POST only");
            return;
        }
        Properties body = readProperties(exchange);
        String token = body.getProperty("token", "");
        int keepGroups = parseKeepGroups(body.getProperty("keepGroups", "0"));
        StagedUpload upload = staged.remove(token);
        if (upload == null || upload.expired()) {
            if (upload != null) upload.delete();
            throw new BadRequestException("Unknown or expired upload token");
        }
        BackupManifest manifest;
        try {
            manifest = BackupManifest.fromString(body.getProperty("manifest", ""));
        } catch (IOException e) {
            upload.delete();
            throw new BadRequestException("Invalid manifest: " + e.getMessage());
        }
        BackupCatalog.Snapshot snapshot = manifest.snapshot();
        if (snapshot == null) {
            upload.delete();
            throw new BadRequestException("Manifest does not declare a snapshot identity");
        }
        Path userDirectory = NetworkUsers.userDirectory(storageRoot, username);
        String archiveName = snapshot.archiveName();
        if (!archiveName.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,199}")) {
            upload.delete();
            throw new BadRequestException("Unsafe archive name: " + archiveName);
        }
        Path archive = userDirectory.resolve(archiveName);
        Path manifestSidecar = userDirectory.resolve(archiveName + ".manifest.properties");
        if (Files.exists(archive, LinkOption.NOFOLLOW_LINKS) || Files.exists(manifestSidecar, LinkOption.NOFOLLOW_LINKS)) {
            upload.delete();
            throw new BadRequestException("Archive already exists on the server: " + archiveName);
        }
        List<String> warnings = new ArrayList<>();
        try {
            Files.move(upload.path(), archive, StandardCopyOption.ATOMIC_MOVE);
            writeAtomically(manifestSidecar, body.getProperty("manifest", ""));
            try {
                BackupCatalog.publish(userDirectory, snapshot);
            } catch (IOException | RuntimeException e) {
                Files.deleteIfExists(archive);
                Files.deleteIfExists(manifestSidecar);
                throw e;
            }
            writeAtomically(userDirectory.resolve(".backpack-manifest.properties"), body.getProperty("manifest", ""));
            BackupCatalog.prune(userDirectory, snapshot.taskId(), keepGroups, warnings::add);
        } finally {
            upload.delete();
        }
        Properties response = new Properties();
        response.setProperty("stored", archiveName);
        response.setProperty("snapshotId", snapshot.snapshotId());
        response.setProperty("sequence", Integer.toString(snapshot.sequence()));
        for (int i = 0; i < warnings.size(); i++) response.setProperty("warning." + i, warnings.get(i));
        sendProperties(exchange, 200, response);
    }

    private void handleSnapshots(HttpExchange exchange, String username) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "GET only");
            return;
        }
        Path userDirectory = NetworkUsers.userDirectory(storageRoot, username);
        List<Map.Entry<BackupCatalog.Snapshot, Long>> snapshots = new ArrayList<>();
        if (Files.isDirectory(userDirectory)) {
            try (Stream<Path> files = Files.list(userDirectory)) {
                for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".snapshot.properties")).toList()) {
                    try {
                        BackupCatalog.Snapshot snapshot = readSnapshotIndex(file);
                        snapshots.add(Map.entry(snapshot, Files.size(userDirectory.resolve(snapshot.archiveName()))));
                    } catch (IOException | RuntimeException ignored) {
                        // Unreadable records are invisible to listings but never deleted here.
                    }
                }
            }
        }
        snapshots.sort(Comparator.comparing((Map.Entry<BackupCatalog.Snapshot, Long> entry) -> entry.getKey().createdAt()).reversed()
                .thenComparing(entry -> entry.getKey().sequence(), Comparator.reverseOrder()));
        Properties response = new Properties();
        response.setProperty("snapshot.count", Integer.toString(snapshots.size()));
        for (int i = 0; i < snapshots.size(); i++) {
            BackupCatalog.Snapshot snapshot = snapshots.get(i).getKey();
            String prefix = "snapshot." + i + ".";
            response.setProperty(prefix + "taskId", snapshot.taskId());
            response.setProperty(prefix + "snapshotId", snapshot.snapshotId());
            response.setProperty(prefix + "groupId", snapshot.groupId());
            response.setProperty(prefix + "parentId", snapshot.parentId());
            response.setProperty(prefix + "sequence", Integer.toString(snapshot.sequence()));
            response.setProperty(prefix + "archiveName", snapshot.archiveName());
            response.setProperty(prefix + "createdAt", snapshot.createdAt().toString());
            response.setProperty(prefix + "bytes", Long.toString(snapshots.get(i).getValue()));
        }
        sendProperties(exchange, 200, response);
    }

    private void handleManifest(HttpExchange exchange, String username) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "GET only");
            return;
        }
        Path userDirectory = NetworkUsers.userDirectory(storageRoot, username);
        Path sidecar = userDirectory.resolve(safeName(query(exchange, "name")) + ".manifest.properties");
        if (!Files.isRegularFile(sidecar, LinkOption.NOFOLLOW_LINKS)) throw new NoSuchFileException(sidecar.toString());
        sendBytes(exchange, 200, Files.readAllBytes(sidecar));
    }

    private void handleDownload(HttpExchange exchange, String username) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "GET only");
            return;
        }
        Path userDirectory = NetworkUsers.userDirectory(storageRoot, username);
        Path archive = userDirectory.resolve(safeName(query(exchange, "name")));
        if (!Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS)) throw new NoSuchFileException(archive.toString());
        exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
        exchange.sendResponseHeaders(200, Files.size(archive));
        try (OutputStream out = exchange.getResponseBody()) {
            Files.copy(archive, out);
        }
    }

    private void handleDelete(HttpExchange exchange, String username) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "POST only");
            return;
        }
        Properties body = readProperties(exchange);
        String groupId = body.getProperty("groupId", "");
        if (groupId.isBlank()) throw new BadRequestException("Missing groupId");
        Path userDirectory = NetworkUsers.userDirectory(storageRoot, username);
        List<BackupCatalog.Snapshot> snapshots = new ArrayList<>();
        try (Stream<Path> files = Files.list(userDirectory)) {
            for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".snapshot.properties")).toList()) {
                try {
                    snapshots.add(readSnapshotIndex(file));
                } catch (IOException | RuntimeException ignored) {
                }
            }
        }
        BackupCatalog.Snapshot newest = snapshots.stream()
                .max(Comparator.comparing(BackupCatalog.Snapshot::createdAt).thenComparing(BackupCatalog.Snapshot::sequence))
                .orElse(null);
        List<BackupCatalog.Snapshot> members = snapshots.stream()
                .filter(snapshot -> snapshot.groupId().equals(groupId)).toList();
        if (members.isEmpty()) throw new BadRequestException("Unknown group: " + groupId);
        if (newest != null && members.stream().anyMatch(member -> member.snapshotId().equals(newest.snapshotId()))) {
            throw new BadRequestException("Refusing to delete the group that contains the newest snapshot");
        }
        List<String> warnings = new ArrayList<>();
        int deleted = 0;
        for (BackupCatalog.Snapshot member : members) {
            try {
                Files.delete(userDirectory.resolve(member.archiveName()));
                Files.delete(userDirectory.resolve(member.archiveName() + ".manifest.properties"));
                Files.delete(userDirectory.resolve(member.archiveName() + ".snapshot.properties"));
                deleted++;
            } catch (IOException e) {
                warnings.add("Could not delete " + member.archiveName() + ": " + e.getMessage());
            }
        }
        Properties response = new Properties();
        response.setProperty("deleted", Integer.toString(deleted));
        for (int i = 0; i < warnings.size(); i++) response.setProperty("warning." + i, warnings.get(i));
        sendProperties(exchange, 200, response);
    }

    private BackupCatalog.Snapshot readSnapshotIndex(Path file) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        if (!"1".equals(properties.getProperty("version"))) throw new IOException("Unknown snapshot index version");
        return new BackupCatalog.Snapshot(properties.getProperty("taskId"), properties.getProperty("snapshotId"),
                properties.getProperty("groupId"), properties.getProperty("parentId"),
                Integer.parseInt(properties.getProperty("sequence")), properties.getProperty("archiveName"),
                Instant.parse(properties.getProperty("createdAt")));
    }

    private static void cleanupStaleUploads(Path storageRoot) throws IOException {
        Path users = storageRoot.resolve("users");
        if (!Files.isDirectory(users)) return;
        try (Stream<Path> directories = Files.list(users)) {
            for (Path directory : directories.filter(Files::isDirectory).toList()) {
                try (Stream<Path> files = Files.list(directory)) {
                    for (Path file : files.filter(p -> p.getFileName().toString().startsWith(".incoming-")).toList()) {
                        Files.deleteIfExists(file);
                    }
                }
            }
        }
    }

    private static void ensureKeystore(Path keystore) throws IOException {
        if (Files.exists(keystore)) return;
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
        Path keytool = Path.of(System.getProperty("java.home"), "bin", windows ? "keytool.exe" : "keytool");
        if (!Files.isRegularFile(keytool)) throw new IOException("keytool not found next to the Java runtime; create " + keystore + " manually");
        ProcessBuilder builder = new ProcessBuilder(keytool.toString(),
                "-genkeypair", "-alias", "backupserver", "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "3650", "-dname", "CN=Backup Server,O=Backup,C=US",
                "-ext", "SAN=dns:localhost,ip:127.0.0.1",
                "-storetype", "PKCS12", "-keystore", keystore.toString(),
                "-storepass", new String(KEYSTORE_PASSWORD), "-keypass", new String(KEYSTORE_PASSWORD), "-noprompt");
        builder.redirectErrorStream(true);
        try {
            Process process = builder.start();
            try (InputStream output = process.getInputStream()) { output.readAllBytes(); }
            if (process.waitFor() != 0) throw new IOException("keytool could not create the server certificate");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while creating the server certificate", e);
        }
    }

    private static SSLContext sslContext(Path keystore) throws IOException {
        try {
            KeyStore store = KeyStore.getInstance("PKCS12");
            try (InputStream in = Files.newInputStream(keystore)) {
                store.load(in, KEYSTORE_PASSWORD);
            }
            KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            factory.init(store, KEYSTORE_PASSWORD);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(factory.getKeyManagers(), null, null);
            return context;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Unable to load the server TLS identity", e);
        }
    }

    private static Properties readProperties(HttpExchange exchange) throws IOException {
        byte[] bytes;
        try (InputStream in = exchange.getRequestBody()) {
            bytes = in.readNBytes(MAX_REQUEST_BODY + 1);
        }
        if (bytes.length > MAX_REQUEST_BODY) throw new BadRequestException("Request body too large");
        Properties properties = new Properties();
        properties.load(new ByteArrayInputStream(bytes));
        return properties;
    }

    private static void writeAtomically(Path target, String content) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), ".sidecar-", ".tmp");
        try {
            Files.write(temporary, content.getBytes(StandardCharsets.UTF_8));
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static int parseKeepGroups(String value) {
        int keepGroups;
        try {
            keepGroups = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new BadRequestException("Invalid keepGroups: " + value);
        }
        if (keepGroups < 0) throw new BadRequestException("keepGroups must be nonnegative");
        return keepGroups;
    }

    private static String safeName(String name) {
        if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,199}")) {
            throw new BadRequestException("Unsafe name parameter");
        }
        return name;
    }

    private static String query(HttpExchange exchange, String key) {
        Map<String, String> parameters = new HashMap<>();
        String query = exchange.getRequestURI().getRawQuery();
        if (query != null) {
            for (String pair : query.split("&")) {
                int equals = pair.indexOf('=');
                if (equals > 0) {
                    parameters.put(urlDecode(pair.substring(0, equals)), urlDecode(pair.substring(equals + 1)));
                }
            }
        }
        return parameters.get(key);
    }

    private static String urlDecode(String value) {
        return java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static void sendProperties(HttpExchange exchange, int status, Properties properties) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        properties.store(buffer, "Backup network response");
        sendBytes(exchange, status, buffer.toByteArray());
    }

    private static void sendBytes(HttpExchange exchange, int status, byte[] bytes) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void sendError(HttpExchange exchange, int status, String message) {
        try {
            Properties response = new Properties();
            response.setProperty("error", message == null ? "Unknown error" : message);
            sendProperties(exchange, status, response);
        } catch (IOException ignored) {
            // The connection is gone; nothing further to report.
        }
    }

    private static final class BadRequestException extends RuntimeException {
        BadRequestException(String message) { super(message); }
    }

    private record StagedUpload(Path path, long createdAt) {
        boolean expired() {
            return System.currentTimeMillis() - createdAt > UPLOAD_TTL_MILLIS;
        }

        void delete() {
            try {
                Files.deleteIfExists(path);
            } catch (IOException ignored) {
            }
        }
    }
}
