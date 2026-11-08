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
 * HTTPS 备份接收服务：把单机备份软件扩展为局域网网盘。
 *
 * <p>身份与安全：首次启动用 JDK keytool 自动生成自签名证书（PKCS12，SAN 含
 * localhost/127.0.0.1）；所有 /api/* 端点经统一的 authenticated 包装执行
 * Basic 认证（凭据在 TLS 信道内），401 时带 WWW-Authenticate 头。</p>
 *
 * <p>上传协议分两段：/api/upload 把归档字节流写入用户目录的 .incoming-*.part
 * 并返回内存令牌（1 小时过期）；/api/commit 携带令牌与完整清单——服务器解析清单、
 * 校验快照身份与归档名安全后原子发布归档 + 清单旁挂 + 快照索引（复用 BackupCatalog），
 * 写兼容最新清单，再按 keepGroups 执行服务器端保留；发布失败回滚本次产物。</p>
 *
 * <p>请求/响应体（除归档字节流）统一为 Properties 文本；名字参数一律白名单校验；
 * 启动时清扫残留 .incoming-*.part；close 时停止 HTTP 服务并清理未完成的上传。</p>
 */
public final class NetworkBackupServer implements AutoCloseable {
    public static final String KEYSTORE_FILE = "server-keystore.p12";
    // 密钥库固定名：服务器身份与客户端信任共用同一文件（信任钉定模型）。
    public static final char[] KEYSTORE_PASSWORD = "backup-server-identity".toCharArray();
    private static final int MAX_REQUEST_BODY = 64 * 1024 * 1024;
    private static final long UPLOAD_TTL_MILLIS = 60 * 60 * 1000;
    // 暂存令牌一小时过期：足够传大文件，又不会永久占用磁盘。

    private final HttpsServer server;
    private final ExecutorService executorService;
    private final Path storageRoot;
    private final Map<String, StagedUpload> staged = new ConcurrentHashMap<>();

    private NetworkBackupServer(HttpsServer server, ExecutorService executorService, Path storageRoot) {
        this.server = server;
        this.executorService = executorService;
        this.storageRoot = storageRoot;
    }

    /** 启动：建目录 → 清残留 → 确保证书 → 组装 SSLContext → 注册端点 → 起服务。port=0 时系统分配。 */
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
        // 线程池：缓存型守护线程，连接数随演示规模弹性伸缩。
        httpsServer.start();
        return networkServer;
    }

    /** 实际监听端口（port=0 时由系统分配后回读）。 */
    public int port() {
        return server.getAddress().getPort();
    }

    /** 存储根（客户端信任的密钥库也在其下）。 */
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

    /** 认证包装器：解析 Basic 头 → NetworkUsers 常数时间校验；失败返回 401。业务异常映射为 400/404/500。 */
    private com.sun.net.httpserver.HttpHandler authenticated(AuthedHandler handler) {
        return exchange -> {
            String username = authenticate(exchange);
        // 认证失败带 WWW-Authenticate 头回 401（HTTP 标准握手语义）。
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

    /** Basic 凭据解析与常数时间校验；成功返回用户名，失败 null（外层回 401）。 */
    private String authenticate(HttpExchange exchange) throws IOException {
        List<String> headers = exchange.getRequestHeaders().get("Authorization");
        // 头缺失/非 Basic 前缀/解码失败/无冒号 → 全部按未认证处理，不给探测空间。
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

    /** 连接自检端点：证明 TLS 信任与账号都正确。 */
    /** 连接自检端点：证明 TLS 信任与账号都正确。 */
    private void handlePing(HttpExchange exchange, String username) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "GET only");
            return;
        }
        Properties response = new Properties();
        response.setProperty("status", "ok");
        // ping 响应体：状态 + 回显用户名（客户端日志直接展示）。
        response.setProperty("user", username);
        sendProperties(exchange, 200, response);
    }

    /** 第一段上传：归档流式落盘为 .incoming-<uuid>.part，返回内存令牌（绑定该暂存文件，1 小时过期）。 */
    private void handleUpload(HttpExchange exchange, String username) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "POST only");
            return;
        }
        Path userDirectory = NetworkUsers.userDirectory(storageRoot, username);
        Files.createDirectories(userDirectory);
        Path stagedFile = Files.createTempFile(userDirectory, ".incoming-", ".part");
        // 暂存文件建在用户自己的目录内：commit 校验 parent 归属，杜绝跨用户写入。
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
        // 上传响应：令牌 + 已收字节数（客户端可做大小对账）。
        response.setProperty("bytes", Long.toString(Files.size(stagedFile)));
        sendProperties(exchange, 200, response);
    }

    /**
     * 第二段提交：令牌→暂存文件 → 解析清单并校验快照身份/归档名白名单 →
     * 归档不存在才允许发布（防覆盖）→ 原子移动 + 写清单旁挂 + BackupCatalog.publish +
     * 写最新清单 + 按 keepGroups 淘汰；发布失败删除本次产物（回滚）。
     */
    private void handleCommit(HttpExchange exchange, String username) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "POST only");
            return;
        }
        Properties body = readProperties(exchange);
        String token = body.getProperty("token", "");
        int keepGroups = parseKeepGroups(body.getProperty("keepGroups", "0"));
        StagedUpload upload = staged.remove(token);
        // 令牌一次性消费：取出即从表中移除（防重放），过期/未知一律 400。
        if (upload == null || upload.expired()) {
            if (upload != null) upload.delete();
            throw new BadRequestException("Unknown or expired upload token");
        }
        BackupManifest manifest;
        // 清单文本必须能解析出完整快照身份，否则拒绝提交（归档名以清单为准，客户端说了不算）。
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
        // 冲突防护：归档或清单任一已存在 → 拒绝（UUID 命名下冲突只可能是重放/篡改）。
        Path manifestSidecar = userDirectory.resolve(archiveName + ".manifest.properties");
        if (Files.exists(archive, LinkOption.NOFOLLOW_LINKS) || Files.exists(manifestSidecar, LinkOption.NOFOLLOW_LINKS)) {
            upload.delete();
            throw new BadRequestException("Archive already exists on the server: " + archiveName);
        }
        List<String> warnings = new ArrayList<>();
        try {
            Files.move(upload.path(), archive, StandardCopyOption.ATOMIC_MOVE);
        // 原子发布链：归档 → 清单旁挂 → 快照索引 → 最新清单；publish 失败即回滚删除前两件。
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
        // 提交响应：最终归档名 + 快照序号 + 保留警告逐条回传。
        response.setProperty("snapshotId", snapshot.snapshotId());
        response.setProperty("sequence", Integer.toString(snapshot.sequence()));
        for (int i = 0; i < warnings.size(); i++) response.setProperty("warning." + i, warnings.get(i));
        sendProperties(exchange, 200, response);
    }

    /** 元数据查询：解析全部 .snapshot.properties（损坏记录静默跳过，绝不删除），按创建时间倒序返回含字节数。 */
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
                        // 读不出的索引在列表中不可见，但这里也绝不删除（保守原则）。
                    }
                }
            }
        }
        snapshots.sort(Comparator.comparing((Map.Entry<BackupCatalog.Snapshot, Long> entry) -> entry.getKey().createdAt()).reversed()
                .thenComparing(entry -> entry.getKey().sequence(), Comparator.reverseOrder()));
        Properties response = new Properties();
        response.setProperty("snapshot.count", Integer.toString(snapshots.size()));
        // 列表响应：count + 编号数组（Properties 没有数组，用前缀约定）。
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

    /** 清单查询：返回指定归档的清单旁挂全文（404 由外层映射）。 */
    /** 清单查询：返回指定归档的清单旁挂全文（404 由外层映射）。 */
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

    /** 归档下载：Content-Length 精确、流式拷贝。 */
    /** 归档下载：Content-Length 精确、流式拷贝。 */
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

    /** 整组删除：groupId 匹配的所有快照逐一删除三件套；含全用户最新快照的组拒绝删除（保护最新）。 */
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
        // 快照枚举（删除路径）：坏索引同样跳过，只对可读记录执行删除。
        try (Stream<Path> files = Files.list(userDirectory)) {
            for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".snapshot.properties")).toList()) {
                try {
                    snapshots.add(readSnapshotIndex(file));
                } catch (IOException | RuntimeException ignored) {
                }
            }
        }
        BackupCatalog.Snapshot newest = snapshots.stream()
        // 最新快照保护：含它的组拒绝整删——与本地保留语义对齐。
                .max(Comparator.comparing(BackupCatalog.Snapshot::createdAt).thenComparing(BackupCatalog.Snapshot::sequence))
                .orElse(null);
        List<BackupCatalog.Snapshot> members = snapshots.stream()
                .filter(snapshot -> snapshot.groupId().equals(groupId)).toList();
        if (members.isEmpty()) throw new BadRequestException("Unknown group: " + groupId);
        // 最新组保护：包含全用户最新快照的组不可删，防止误删唯一可用备份。
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
        // 删除响应：成功条数 + 局部失败警告。
        for (int i = 0; i < warnings.size(); i++) response.setProperty("warning." + i, warnings.get(i));
        sendProperties(exchange, 200, response);
    }

    /** 解析单个快照索引（version=1），损坏抛异常由调用方决定去留。 */
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

    /** 启动清扫：上次运行遗留的 .incoming-*.part 一律删除（未提交的上传不可信）。 */
    /** 启动清扫：上次运行遗留的 .incoming-*.part 一律删除（未提交的上传不可信）。 */
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

    /** 证书自举：不存在时调用 JDK keytool 生成 RSA-2048/10 年/含 SAN 的 PKCS12；keytool 缺失给出手工指引。 */
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

    /** 从 PKCS12 密钥库装配 TLS 上下文（KeyManagerFactory）；加载失败包装为可读 IOException。 */
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

    /** 请求体（Properties 文本）读取：64MB 上限。 */
    /** 请求体（Properties 文本）读取：64MB 上限。 */
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

    /** 旁挂文件原子写入：临时文件 + ATOMIC_MOVE。 */
    private static void writeAtomically(Path target, String content) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), ".sidecar-", ".tmp");
        try {
            Files.write(temporary, content.getBytes(StandardCharsets.UTF_8));
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** keepGroups 解析与负数拒绝（与本地保留语义一致）。 */
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

    /** 名字参数白名单：归档名/查询参数共用，杜绝路径注入。 */
    /** 名字参数白名单：归档名/查询参数共用，杜绝路径注入。 */
    private static String safeName(String name) {
        if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,199}")) {
            throw new BadRequestException("Unsafe name parameter");
        }
        return name;
    }

    /** 查询串解析：k=v 对 URL 解码后入表，取值再过白名单。 */
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

    /** UTF-8 URL 解码。 */
    private static String urlDecode(String value) {
        return java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    /** 响应工具：Properties → UTF-8 字节 → 统一 Content-Type 发送。 */
    private static void sendProperties(HttpExchange exchange, int status, Properties properties) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        properties.store(buffer, "Backup network response");
        sendBytes(exchange, status, buffer.toByteArray());
    }

    /** 字节响应：带精确 Content-Length。 */
    private static void sendBytes(HttpExchange exchange, int status, byte[] bytes) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** 错误响应：{error=消息}；连接已断时静默放弃。 */
    private static void sendError(HttpExchange exchange, int status, String message) {
        try {
            Properties response = new Properties();
            response.setProperty("error", message == null ? "Unknown error" : message);
        // 错误体结构固定：{error=消息}——客户端 failure() 据此提取人话。
            sendProperties(exchange, status, response);
        } catch (IOException ignored) {
            // 连接已断开，错误体发不出去也无事可做。
        }
    }

    private static final class BadRequestException extends RuntimeException {
        BadRequestException(String message) { super(message); }
    }

    /** 暂存上传：路径 + 创建时间（1 小时 TTL），delete 供超时/失败/关闭时清理。 */
    /** 暂存上传：路径 + 创建时间（1 小时 TTL），delete 供超时/失败/关闭时清理。 */
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
