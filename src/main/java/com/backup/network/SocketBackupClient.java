package com.backup.network;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;

public final class SocketBackupClient {
    public void upload(Path archive, String host, int port) throws IOException {
        try (Socket socket = new Socket(host, port);
             DataOutputStream outputStream = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()))) {
            outputStream.writeUTF(archive.getFileName().toString());
            outputStream.writeLong(Files.size(archive));
            Files.copy(archive, outputStream);
            outputStream.flush();
        }
    }
}
