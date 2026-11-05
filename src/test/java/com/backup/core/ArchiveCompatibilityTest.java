package com.backup.core;

import com.backup.io.ArchiveService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.backup.testing.FileSystemCapabilities.*;

/** Fixtures deliberately do not use the current archive or manifest writers. */
class ArchiveCompatibilityTest {
    @TempDir Path temp;
    private static final byte[] CONTENT = "legacy fixture\n".getBytes(StandardCharsets.UTF_8);
    private static final long MODIFIED = 1_600_000_000_000L;

    private String manifest(String compression, String path) {
        return "version=1\nsourceRoot=/old/source\ncreatedAt=2020-09-13T12:26:40Z\n"
                + "compressionType=" + compression + "\nincremental=false\nencrypted=false\nentry.count=1\n"
                + "entry.0.path=" + path + "\nentry.0.directory=false\nentry.0.size=" + CONTENT.length
                + "\nentry.0.lastModifiedMillis=" + MODIFIED + "\n";
    }

    private Path zip(String name, String manifest, String payload) throws IOException {
        Path archive = temp.resolve(name);
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(archive))) {
            out.putNextEntry(new ZipEntry("BACKPACK-MANIFEST.properties"));
            out.write(manifest.getBytes(StandardCharsets.UTF_8)); out.closeEntry();
            if (payload != null) {
                out.putNextEntry(new ZipEntry("data/" + payload)); out.write(CONTENT); out.closeEntry();
            }
        }
        return archive;
    }

    private void string(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8); out.writeInt(bytes.length); out.write(bytes);
    }

    private Path gzip(String name, String manifest, String payload, int count) throws IOException {
        Path archive = temp.resolve(name);
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(archive)))) {
            string(out, "BACKPACK-GZIP-1"); byte[] bytes = manifest.getBytes(StandardCharsets.UTF_8);
            out.writeInt(bytes.length); out.write(bytes); out.writeInt(count);
            for (int i = 0; i < count; i++) {
                string(out, payload); out.writeBoolean(false); out.writeLong(CONTENT.length);
                out.writeLong(MODIFIED); out.write(CONTENT);
            }
        }
        return archive;
    }

    @Test void restoresHandcraftedVersionOneZipWithoutInventingAbsentTimes() throws Exception {
        Path out = temp.resolve("out");
        BackupManifest result = new ArchiveService().restore(zip("old.zip", manifest("ZIP", "old.txt"), "old.txt"), out);
        assertArrayEquals(CONTENT, Files.readAllBytes(out.resolve("old.txt")));
        assertEquals(MODIFIED, Files.getLastModifiedTime(out.resolve("old.txt")).toMillis());
        assertEquals(EntryType.FILE, result.entries().getFirst().type());
        assertNull(result.entries().getFirst().lastAccessMillis());
        assertNull(result.entries().getFirst().creationMillis());
    }

    @Test void restoresHandcraftedVersionOneGzip() throws Exception {
        Path out = temp.resolve("out");
        BackupManifest result = new ArchiveService().restore(gzip("old.gz", manifest("GZIP", "old.txt"), "old.txt", 1), out);
        assertArrayEquals(CONTENT, Files.readAllBytes(out.resolve("old.txt")));
        assertEquals(MODIFIED, Files.getLastModifiedTime(out.resolve("old.txt")).toMillis());
        assertEquals(CompressionType.GZIP, result.compressionType());
    }

    @Test void traversalManifestCannotOverwriteOutsideFile() throws Exception {
        Path sentinel = Files.writeString(temp.resolve("outside"), "keep");
        Path archive = zip("hostile.zip", manifest("ZIP", "../outside"), "../outside");
        assertThrows(IOException.class, () -> new ArchiveService().restore(archive, temp.resolve("out")));
        assertEquals("keep", Files.readString(sentinel));
    }

    @Test void duplicatePayloadIsRejectedEvenWhenManifestCountPermitsTwoRecords() throws Exception {
        String manifest = manifest("GZIP", "old.txt").replace("entry.count=1", "entry.count=2")
                + "entry.1.path=other.txt\nentry.1.size=" + CONTENT.length + "\n";
        Path archive = gzip("duplicate.gz", manifest, "old.txt", 2);
        IOException error = assertThrows(IOException.class, () -> new ArchiveService().restore(archive, temp.resolve("out")));
        assertTrue(error.getMessage().contains("duplicate"));
    }

    @Test void duplicateManifestPathsAreRejectedBeforePayloadWrites() throws Exception {
        String text = manifest("ZIP", "old.txt").replace("entry.count=1", "entry.count=2")
                + "entry.1.path=old.txt\nentry.1.size=" + CONTENT.length + "\n";
        Path out = temp.resolve("out");
        assertThrows(IOException.class, () -> new ArchiveService().restore(zip("duplicate.zip", text, "old.txt"), out));
        assertFalse(Files.exists(out.resolve("old.txt")));
    }

    @Test void malformedManifestReportsIoFailureBeforePayloadWrites() throws Exception {
        Path out = temp.resolve("out");
        Path archive = zip("malformed.zip", manifest("ZIP", "old.txt").replace("entry.0.size=" + CONTENT.length, "entry.0.size=not-a-number"), "old.txt");
        assertThrows(IOException.class, () -> new ArchiveService().restore(archive, out));
        assertFalse(Files.exists(out.resolve("old.txt")));
    }

    @Test void encryptedZipPreservesLinks() throws Exception { encryptedLinks(CompressionType.ZIP); }
    @Test void encryptedGzipPreservesLinks() throws Exception { encryptedLinks(CompressionType.GZIP); }

    private void encryptedLinks(CompressionType compression) throws Exception {
        assumeHardLinks(temp);
        assumeSymbolicLinks(temp);
        Path source = Files.createDirectory(temp.resolve("source"));
        Files.writeString(source.resolve("a"), "linked data");
        Files.createLink(source.resolve("hard"), source.resolve("a"));
        assumeFileKeys(source.resolve("a"));
        Files.createSymbolicLink(source.resolve("relative"), Path.of("a"));
        Files.createSymbolicLink(source.resolve("broken"), Path.of("missing"));
        char[] password = "fixture-password".toCharArray();
        BackupService service = new BackupService();
        BackupResult archive = service.backup(new BackupOptions(source, temp.resolve("backups"), "links", compression, false, true, password));
        Path out = temp.resolve("out"); service.restore(archive.archivePath(), out, password);
        assertEquals("linked data", Files.readString(out.resolve("a")));
        assertTrue(Files.isSameFile(out.resolve("a"), out.resolve("hard")));
        assertEquals(Path.of("a"), Files.readSymbolicLink(out.resolve("relative")));
        assertEquals(Path.of("missing"), Files.readSymbolicLink(out.resolve("broken")));
        assertFalse(Files.exists(out.resolve("broken")));
    }
}
