package com.backup.core;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;

/** Ownership catalog for completed archives. Retention is explicit and operates on full chains. */
public final class BackupCatalog {
    private static final String INDEX = ".snapshot.properties";
    private static final String MANIFEST = ".manifest.properties";
    private BackupCatalog() {}

    public record Snapshot(String taskId, String snapshotId, String groupId, String parentId,
                           int sequence, String archiveName, Instant createdAt) {}

    public static void publish(Path destination, Snapshot snapshot) throws IOException {
        validate(snapshot);
        Path directory = directory(destination);
        verifyPair(directory, snapshot);
        Path target = directory.resolve(snapshot.archiveName() + INDEX);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !regular(target))
            throw new IOException("Unsafe catalog entry: " + target);
        Path temporary = Files.createTempFile(directory, ".snapshot-", ".tmp");
        try {
            try (Writer out = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                properties(snapshot).store(out, "Completed backup snapshot");
            }
            // Never expose a partially written index, including on filesystems without atomic moves.
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public static void prune(Path destination, String taskId, int keepGroups, Consumer<String> warnings)
            throws IOException {
        identifier(taskId); Objects.requireNonNull(warnings, "warnings");
        if (keepGroups < 0) throw new IllegalArgumentException("keepGroups must be nonnegative");
        if (keepGroups == 0) return;
        Path directory = directory(destination);
        List<Snapshot> snapshots = new ArrayList<>();
        boolean corrupt = false;
        try (var files = Files.list(directory)) {
            for (Path file : files.filter(p -> p.getFileName().toString().endsWith(INDEX)).toList()) {
                try {
                    if (!regular(file)) throw new IOException("Not a regular index");
                    Properties p = read(file);
                    if (!"1".equals(p.getProperty("version"))) throw new IOException("Unknown index version");
                    Snapshot s = parse(p); validate(s);
                    if (!file.getFileName().toString().equals(s.archiveName() + INDEX))
                        throw new IOException("Index filename mismatch");
                    snapshots.add(s);
                } catch (IOException | RuntimeException ex) {
                    warnings.accept("Preserving catalog with invalid entry " + file.getFileName() + ": " + ex.getMessage());
                    corrupt = true;
                }
            }
        }
        // An unreadable record may name any group: do not guess which archives it owns.
        if (corrupt) return;
        Map<String, List<Snapshot>> groups = new HashMap<>();
        Set<String> duplicateIds = new HashSet<>();
        Set<String> ids = new HashSet<>();
        for (Snapshot s : snapshots) {
            String key = s.taskId() + "/" + s.snapshotId();
            if (!ids.add(key)) duplicateIds.add(key);
            if (s.taskId().equals(taskId)) groups.computeIfAbsent(s.groupId(), k -> new ArrayList<>()).add(s);
        }
        List<List<Snapshot>> valid = new ArrayList<>();
        for (var members : groups.values()) {
            members.sort(Comparator.comparingInt(Snapshot::sequence));
            try {
                validateChain(members);
                for (Snapshot s : members) {
                    if (duplicateIds.contains(s.taskId() + "/" + s.snapshotId()))
                        throw new IOException("Duplicate snapshot identity");
                    verifyPair(directory, s);
                }
                valid.add(members);
            } catch (IOException | RuntimeException ex) {
                warnings.accept("Preserving invalid group " + members.getFirst().groupId() + ": " + ex.getMessage());
            }
        }
        Comparator<Snapshot> chronological = Comparator.comparing(Snapshot::createdAt)
                .thenComparing(Snapshot::groupId).thenComparing(Snapshot::snapshotId);
        valid.sort((a, b) -> chronological.compare(b.getFirst(), a.getFirst()));
        Snapshot latest = snapshots.stream().filter(s -> s.taskId().equals(taskId)).max(chronological).orElse(null);
        for (int i = keepGroups; i < valid.size(); i++) {
            List<Snapshot> members = valid.get(i);
            if (latest != null && members.getFirst().groupId().equals(latest.groupId())) continue;
            for (int j = members.size() - 1; j >= 0; j--) {
                Snapshot s = members.get(j);
                try {
                    verifyPair(directory, s);
                    Path index = directory.resolve(s.archiveName() + INDEX);
                    if (!regular(index) || !s.equals(parse(read(index))))
                        throw new IOException("Index changed during retention");
                    Files.delete(directory.resolve(s.archiveName()));
                    Files.delete(directory.resolve(s.archiveName() + MANIFEST));
                    Files.delete(index);
                } catch (IOException | RuntimeException ex) {
                    warnings.accept("Stopped pruning group " + s.groupId() + ": " + ex.getMessage());
                    break;
                }
            }
        }
    }

    private static void validateChain(List<Snapshot> members) throws IOException {
        Snapshot root = members.getFirst();
        if (root.sequence() != 1) throw new IOException("Missing full root");
        for (int i = 0; i < members.size(); i++) {
            Snapshot s = members.get(i);
            if (s.sequence() != i + 1 || (i > 0 && !s.parentId().equals(members.get(i - 1).snapshotId())))
                throw new IOException("Incomplete or branched chain");
        }
    }

    private static Path directory(Path path) throws IOException {
        Objects.requireNonNull(path, "destination");
        for (Path part : path) if (part.toString().equals("..") || part.toString().equals("."))
            throw new IllegalArgumentException("Destination contains traversal");
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Destination is not a regular directory");
        return path.toRealPath();
    }

    private static boolean regular(Path path) { return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS); }
    private static Properties read(Path path) throws IOException {
        Properties p = new Properties();
        try (Reader in = Files.newBufferedReader(path, StandardCharsets.UTF_8)) { p.load(in); }
        return p;
    }
    private static void verifyPair(Path directory, Snapshot s) throws IOException {
        Path archive = directory.resolve(s.archiveName()), manifest = directory.resolve(s.archiveName() + MANIFEST);
        if (!regular(archive) || !regular(manifest)) throw new IOException("Missing or unsafe archive/manifest pair");
        Properties p = read(manifest);
        if (!"2".equals(p.getProperty("version"))) throw new IOException("Unsupported manifest version");
        Properties expected = properties(s);
        for (String key : List.of("taskId", "snapshotId", "groupId", "parentId", "sequence", "archiveName"))
            if (!expected.getProperty(key).equals(p.getProperty(key))) throw new IOException("Manifest identity mismatch: " + key);
    }
    private static void identifier(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,199}"))
            throw new IllegalArgumentException("Unsafe identifier");
    }
    private static void validate(Snapshot s) {
        Objects.requireNonNull(s, "snapshot");
        identifier(s.taskId());identifier(s.snapshotId());identifier(s.groupId());
        Objects.requireNonNull(s.parentId(), "parentId");Objects.requireNonNull(s.createdAt(), "createdAt");
        if (s.sequence() < 1 || s.sequence() > 10) throw new IllegalArgumentException("Sequence must be 1..10");
        if (s.sequence() == 1) {
            if (!s.parentId().isEmpty() || !s.snapshotId().equals(s.groupId())) throw new IllegalArgumentException("Invalid full root");
        } else { identifier(s.parentId()); if (s.snapshotId().equals(s.parentId()) || s.snapshotId().equals(s.groupId())) throw new IllegalArgumentException("Invalid parent identity"); }
        String name = s.archiveName();
        if (name == null || name.isBlank() || name.equals(".") || name.equals("..") || name.contains("/") || name.contains("\\") || name.indexOf('\0') >= 0 || Path.of(name).isAbsolute())
            throw new IllegalArgumentException("Unsafe archive filename");
    }
    private static Properties properties(Snapshot s) {
        Properties p = new Properties();p.setProperty("version", "1");
        p.setProperty("taskId", s.taskId());p.setProperty("snapshotId", s.snapshotId());
        p.setProperty("groupId", s.groupId());p.setProperty("parentId", s.parentId());
        p.setProperty("sequence", Integer.toString(s.sequence()));p.setProperty("archiveName", s.archiveName());
        p.setProperty("createdAt", s.createdAt().toString());return p;
    }
    private static Snapshot parse(Properties p) {
        return new Snapshot(p.getProperty("taskId"), p.getProperty("snapshotId"), p.getProperty("groupId"),
                p.getProperty("parentId"), Integer.parseInt(p.getProperty("sequence")),
                p.getProperty("archiveName"), Instant.parse(p.getProperty("createdAt")));
    }
}
