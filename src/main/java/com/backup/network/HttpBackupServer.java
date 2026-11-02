package com.backup.network;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class HttpBackupServer implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executorService;

    private HttpBackupServer(HttpServer server, ExecutorService executorService) {
        this.server = server;
        this.executorService = executorService;
    }

    public static HttpBackupServer startHttp(int port, Path uploadDirectory) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        return start(server, uploadDirectory);
    }

    public static HttpBackupServer startHttps(int port, Path uploadDirectory, SSLContext sslContext) throws IOException {
        HttpsServer server = HttpsServer.create(new InetSocketAddress(port), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(sslContext));
        return start(server, uploadDirectory);
    }

    private static HttpBackupServer start(HttpServer server, Path uploadDirectory) throws IOException {
        Files.createDirectories(uploadDirectory);
        ExecutorService executorService = Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r, "backup-http-server");
            thread.setDaemon(true);
            return thread;
        });
        server.createContext("/health", exchange -> {
            byte[] response = "OK".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(response);
            }
        });
        server.createContext("/upload", new UploadHandler(uploadDirectory));
        server.setExecutor(executorService);
        server.start();
        return new HttpBackupServer(server, executorService);
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(1);
        executorService.shutdownNow();
    }

    private static final class UploadHandler implements HttpHandler {
        private final Path uploadDirectory;

        private UploadHandler(Path uploadDirectory) {
            this.uploadDirectory = uploadDirectory;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                send(exchange, 405, "POST only");
                return;
            }
            String rawFileName = firstHeader(exchange, "X-Backup-File-Name");
            String fileName = sanitize(URLDecoder.decode(rawFileName == null ? "backup.bin" : rawFileName, StandardCharsets.UTF_8));
            Path output = uploadDirectory.resolve(fileName).normalize();
            if (!output.startsWith(uploadDirectory.normalize())) {
                send(exchange, 400, "Invalid file name");
                return;
            }
            Files.createDirectories(output.getParent());
            try (InputStream requestBody = exchange.getRequestBody()) {
                Files.copy(requestBody, output);
            }
            send(exchange, 201, "Stored " + fileName);
        }

        private static String firstHeader(HttpExchange exchange, String name) {
            List<String> values = exchange.getRequestHeaders().get(name);
            return values == null || values.isEmpty() ? null : values.getFirst();
        }

        private static void send(HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(bytes);
            }
        }

        private static String sanitize(String fileName) {
            String sanitized = fileName.replaceAll("[^A-Za-z0-9._-]+", "-");
            return sanitized.isBlank() ? "backup.bin" : sanitized;
        }
    }
}
