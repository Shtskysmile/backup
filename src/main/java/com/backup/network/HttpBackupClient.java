package com.backup.network;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;

public final class HttpBackupClient {
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public void upload(Path archive, URI uploadUri) throws IOException, InterruptedException {
        String fileName = archive.getFileName().toString();
        HttpRequest request = HttpRequest.newBuilder(uploadUri)
                .timeout(Duration.ofMinutes(10))
                .header("Content-Type", "application/octet-stream")
                .header("X-Backup-File-Name", URLEncoder.encode(fileName, StandardCharsets.UTF_8))
                .POST(HttpRequest.BodyPublishers.ofFile(archive))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("HTTP upload failed with status " + response.statusCode() + ": " + response.body());
        }
    }
}
