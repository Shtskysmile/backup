package com.backup.network;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class SocketBackupServer implements AutoCloseable {
    private final ServerSocket serverSocket;
    private final Path uploadDirectory;
    private final ExecutorService executorService;
    private volatile boolean running;

    private SocketBackupServer(ServerSocket serverSocket, Path uploadDirectory, ExecutorService executorService) {
        this.serverSocket = serverSocket;
        this.uploadDirectory = uploadDirectory;
        this.executorService = executorService;
    }

    public static SocketBackupServer start(int port, Path uploadDirectory) throws IOException {
        Files.createDirectories(uploadDirectory);
        ServerSocket serverSocket = new ServerSocket(port);
        ExecutorService executorService = Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r, "backup-socket-server");
            thread.setDaemon(true);
            return thread;
        });
        SocketBackupServer server = new SocketBackupServer(serverSocket, uploadDirectory, executorService);
        server.running = true;
        executorService.submit(server::acceptLoop);
        return server;
    }

    public int port() {
        return serverSocket.getLocalPort();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                executorService.submit(() -> handle(socket));
            } catch (IOException e) {
                if (running) {
                    throw new IllegalStateException("Socket server stopped unexpectedly", e);
                }
            }
        }
    }

    private void handle(Socket socket) {
        try (socket;
             DataInputStream inputStream = new DataInputStream(new BufferedInputStream(socket.getInputStream()))) {
            String fileName = sanitize(inputStream.readUTF());
            long size = inputStream.readLong();
            if (size < 0) {
                throw new IOException("Invalid upload size: " + size);
            }
            Path output = uploadDirectory.resolve(fileName).normalize();
            if (!output.startsWith(uploadDirectory.normalize())) {
                throw new IOException("Invalid upload file name: " + fileName);
            }
            Files.createDirectories(output.getParent());
            byte[] buffer = new byte[64 * 1024];
            long remaining = size;
            try (OutputStream outputStream = Files.newOutputStream(output)) {
                while (remaining > 0) {
                    int read = inputStream.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                    if (read == -1) {
                        throw new IOException("Unexpected end of socket upload.");
                    }
                    outputStream.write(buffer, 0, read);
                    remaining -= read;
                }
            }
        } catch (IOException ignored) {
            // Per-connection failures should not stop the server loop.
        }
    }

    private static String sanitize(String fileName) {
        String sanitized = fileName.replaceAll("[^A-Za-z0-9._-]+", "-");
        return sanitized.isBlank() ? "backup.bin" : sanitized;
    }

    @Override
    public void close() {
        running = false;
        try {
            serverSocket.close();
        } catch (IOException ignored) {
            // Server socket close is best effort.
        }
        executorService.shutdownNow();
    }
}
