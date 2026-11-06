package com.backup.network;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Properties;

/**
 * HTTPS client for the network backup service. Credentials travel inside the TLS channel as an
 * HTTP Basic header; the server identity is pinned by explicitly trusting a keystore file
 * (by default the server's own generated keystore), so no certificate authority is required.
 */
/**
 * 网络备份的 HTTPS 客户端封装。
 *
 * <p>安全模型：凭据以 Basic 头随每个请求发送，全程走 TLS；信任根显式钉定到
 * 服务器密钥库文件（TrustManagerFactory 直接加载 PKCS12）——自签名场景下
 * "信任你亲手拷贝的文件"比"信任任何 CA"更直接。主机名校验放宽（端点识别算法置空），
 * 使 localhost 与局域网 IP 皆可用；身份保证来自被钉定的密钥库而非证书链。</p>
 *
 * <p>协议：ping / upload（流式上传，返回令牌）/ commit（令牌+清单+保留数）/
 * snapshots / manifest（404 返回 null）/ download（流式落盘）/ deleteGroup。
 * 非 2xx 响应统一解析 Properties 错误体后抛 IOException。</p>
 */
public final class NetworkBackupClient implements AutoCloseable {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(30);

    private final HttpClient httpClient;
    private final String baseUrl;
    private final String username;
    private final String basicToken;
    private boolean closed;

    /** 连接与认证参数一次性固化：信任库可选（null 时退回 JVM 默认信任，供自管证书场景）。 */
    public NetworkBackupClient(String host, int port, String username, char[] password,
                               Path trustKeystore, char[] trustKeystorePassword) throws IOException {
        this.httpClient = HttpClient.newBuilder()
                .sslContext(sslContext(trustKeystore, trustKeystorePassword))
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        this.baseUrl = "https://" + host + ":" + port;
        this.username = username;
        String credentials = username + ":" + new String(password);
        // Basic 凭据：user:pass → Base64；机密性由 TLS 信道保证。
        this.basicToken = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    /** 当前账号（结果展示用）。 */
    public String username() {
        return username;
    }

    /** GET /api/ping：连接与认证自检。 */
    /** GET /api/ping：连接与认证自检。 */
    public Properties ping() throws IOException, InterruptedException {
        return request("GET", "/api/ping", null, null);
    }

    /** Uploads an archive; returns the staging token for the following commit. */
    public String upload(Path archive) throws IOException, InterruptedException {
        HttpRequest request = builder("/api/upload")
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofFile(archive))
                .build();
        Properties response = execute(request, 200);
        String token = response.getProperty("token");
        if (token == null || token.isBlank()) throw new IOException("Server did not return an upload token");
        return token;
    }

    /** 第二段提交：令牌 + 清单全文 + 保留组数 → 服务器原子发布并返回警告。 */
    /** 第二段提交：令牌 + 清单全文 + 保留组数 → 服务器原子发布并返回警告。 */
    public Properties commit(String token, String manifestText, int keepGroups) throws IOException, InterruptedException {
        Properties body = new Properties();
        body.setProperty("token", token);
        body.setProperty("keepGroups", Integer.toString(Math.max(0, keepGroups)));
        body.setProperty("manifest", manifestText);
        return request("POST", "/api/commit", body, null);
    }

    /** 快照列表：解析 Properties 编号数组为 RemoteSnapshot 记录（含字节数）。 */
    /** 快照列表：解析 Properties 编号数组为 RemoteSnapshot 记录（含字节数）。 */
    public List<RemoteSnapshot> listSnapshots() throws IOException, InterruptedException {
        Properties response = request("GET", "/api/snapshots", null, null);
        int count = Integer.parseInt(response.getProperty("snapshot.count", "0"));
        List<RemoteSnapshot> snapshots = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String prefix = "snapshot." + i + ".";
            snapshots.add(new RemoteSnapshot(
                    response.getProperty(prefix + "taskId"),
                    response.getProperty(prefix + "snapshotId"),
                    response.getProperty(prefix + "groupId"),
                    response.getProperty(prefix + "parentId"),
                    Integer.parseInt(response.getProperty(prefix + "sequence")),
                    response.getProperty(prefix + "archiveName"),
                    java.time.Instant.parse(response.getProperty(prefix + "createdAt")),
                    Long.parseLong(response.getProperty(prefix + "bytes"))));
        }
        return snapshots;
    }

    /** 清单拉取：404 返回 null（服务器无该任务的基线时用于退化全量），其余非 200 抛错。 */
    /** 清单拉取：404 返回 null（服务器无该任务的基线时用于退化全量），其余非 200 抛错。 */
    public String fetchManifest(String archiveName) throws IOException, InterruptedException {
        HttpRequest request = builder("/api/manifest?name=" + encode(archiveName)).GET().build();
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() == 404) return null;
        if (response.statusCode() != 200) throw failure(response);
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    /** 归档下载：流式写目标文件；非 200 时读取错误体给出原因。 */
    /** 归档下载：流式写目标文件；非 200 时读取错误体给出原因。 */
    public void download(String archiveName, Path target) throws IOException, InterruptedException {
        HttpRequest request = builder("/api/download?name=" + encode(archiveName)).GET().build();
        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            try (InputStream body = response.body()) {
                throw failure(body);
            }
        }
        try (InputStream body = response.body(); OutputStream out = Files.newOutputStream(target)) {
            body.transferTo(out);
        }
    }

    /** 整组删除：服务器保护最新组，越界请求返回错误。 */
    public Properties deleteGroup(String groupId) throws IOException, InterruptedException {
        Properties body = new Properties();
        body.setProperty("groupId", groupId);
        return request("POST", "/api/delete", body, null);
    }

    /** 请求基座：URL + 超时 + Basic 认证头（每个请求都带）。 */
    private HttpRequest.Builder builder(String pathAndQuery) {
        return HttpRequest.newBuilder(URI.create(baseUrl + pathAndQuery))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Basic " + basicToken);
    }

    /** 通用请求：Properties 体序列化（自转义）→ 发送 → 断言状态码 → 解析响应。 */
    private Properties request(String method, String pathAndQuery, Properties body, Path ignored) throws IOException, InterruptedException {
        HttpRequest.Builder builder = builder(pathAndQuery).header("Accept", "text/plain");
        if (body != null) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            body.store(buffer, "Backup network request");
            builder.header("Content-Type", "text/plain; charset=utf-8");
            builder.method(method, HttpRequest.BodyPublishers.ofByteArray(buffer.toByteArray()));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return execute(builder.build(), 200);
    }

    /** 发送并断言状态码，解析 Properties 响应体。 */
    private Properties execute(HttpRequest request, int expectedStatus) throws IOException, InterruptedException {
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != expectedStatus) throw failure(response);
        Properties properties = new Properties();
        properties.load(new ByteArrayInputStream(response.body()));
        return properties;
    }

    /** 错误响应转换：尝试读取 Properties 错误体里的 error 字段作为消息。 */
    private IOException failure(HttpResponse<byte[]> response) {
        String message = "HTTP " + response.statusCode();
        try {
            Properties error = new Properties();
            error.load(new ByteArrayInputStream(response.body()));
            String detail = error.getProperty("error");
            if (detail != null) message = message + ": " + detail;
        } catch (IOException ignored) {
        }
        return new IOException(message);
    }

    /** 流式错误体解析（download 的非 200 响应体不是字节数组形态）。 */
    private IOException failure(InputStream body) {
        String message = "HTTP error response";
        try {
            Properties error = new Properties();
            error.load(body);
            String detail = error.getProperty("error");
            if (detail != null) message = detail;
        } catch (IOException ignored) {
        }
        return new IOException(message);
    }

    /** 查询参数 URL 编码。 */
    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** TLS 装配：信任库钉定 + 端点识别算法置空（信任由文件保证，主机名灵活）。 */
    /** TLS 装配：信任库钉定 + 端点识别算法置空（信任由文件保证，主机名灵活）。 */
    private static SSLContext sslContext(Path trustKeystore, char[] trustKeystorePassword) throws IOException {
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            if (trustKeystore != null) {
                KeyStore store = KeyStore.getInstance("PKCS12");
                char[] passwordChars = trustKeystorePassword != null ? trustKeystorePassword : new char[0];
                try (InputStream in = Files.newInputStream(trustKeystore)) {
                    store.load(in, passwordChars);
                }
                TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                factory.init(store);
                context.init(null, factory.getTrustManagers(), null);
            } else {
                context.init(null, null, null);
            }
            // Identity trust is pinned by the provided keystore; hostname checking is relaxed so
            // the built-in certificate also works for LAN addresses.
            SSLParameters parameters = new SSLParameters();
            // 信任已钉定到密钥库文件，主机名校验放宽以支持 localhost/局域网 IP。
            parameters.setEndpointIdentificationAlgorithm(null);
            context.getDefaultSSLParameters().setEndpointIdentificationAlgorithm(null);
            return context;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Unable to configure the TLS trust store", e);
        }
    }

    @Override public void close() {
        closed = true;
    }

    /** 关闭标志（HttpClient 无强制关闭语义，逻辑占位以统一资源管理）。 */
    public boolean isClosed() {
        return closed;
    }

    /** Records one snapshot listed by the server. */
    public record RemoteSnapshot(String taskId, String snapshotId, String groupId, String parentId,
                                 int sequence, String archiveName, java.time.Instant createdAt, long bytes) {}
}
