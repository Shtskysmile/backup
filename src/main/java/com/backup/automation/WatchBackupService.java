package com.backup.automation;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class WatchBackupService implements AutoCloseable {
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ExecutorService executorService = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "backup-watch-service");
        thread.setDaemon(true);
        return thread;
    });
    private WatchService watchService;

    public void start(Path sourceDirectory, Duration debounce, Runnable backupTask, Consumer<String> statusSink) throws IOException {
        Objects.requireNonNull(sourceDirectory, "sourceDirectory");
        Objects.requireNonNull(debounce, "debounce");
        Objects.requireNonNull(backupTask, "backupTask");
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("Watch backup is already running.");
        }
        watchService = sourceDirectory.getFileSystem().newWatchService();
        Map<WatchKey, Path> watchedDirectories = new HashMap<>();
        registerTree(sourceDirectory, watchedDirectories);
        executorService.submit(() -> watchLoop(sourceDirectory, watchedDirectories, debounce, backupTask, statusSink));
    }

    public boolean isRunning() {
        return running.get();
    }

    public void stop() {
        running.set(false);
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException ignored) {
                // Closing a watcher is best effort during shutdown.
            }
        }
    }

    private void watchLoop(
            Path sourceDirectory,
            Map<WatchKey, Path> watchedDirectories,
            Duration debounce,
            Runnable backupTask,
            Consumer<String> statusSink
    ) {
        long debounceMillis = Math.max(250, debounce.toMillis());
        long lastChange = 0;
        boolean dirty = false;
        try {
            while (running.get()) {
                WatchKey key = watchService.poll(500, TimeUnit.MILLISECONDS);
                if (key != null) {
                    Path directory = watchedDirectories.get(key);
                    if (directory != null) {
                        for (WatchEvent<?> event : key.pollEvents()) {
                            WatchEvent.Kind<?> kind = event.kind();
                            if (kind == StandardWatchEventKinds.OVERFLOW) {
                                continue;
                            }
                            Path changed = directory.resolve((Path) event.context());
                            if (kind == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(changed)) {
                                try {
                                    registerTree(changed, watchedDirectories);
                                } catch (IOException e) {
                                    emit(statusSink, "Unable to watch new directory: " + e.getMessage());
                                }
                            }
                            dirty = true;
                            lastChange = System.currentTimeMillis();
                        }
                    }
                    if (!key.reset()) {
                        watchedDirectories.remove(key);
                    }
                }
                if (dirty && System.currentTimeMillis() - lastChange >= debounceMillis) {
                    dirty = false;
                    emit(statusSink, "Change detected under " + sourceDirectory + "; running backup.");
                    backupTask.run();
                }
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            emit(statusSink, "Watch backup stopped: " + e.getMessage());
        } finally {
            running.set(false);
        }
    }

    private void registerTree(Path root, Map<WatchKey, Path> watchedDirectories) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                WatchKey key = dir.register(
                        watchService,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY,
                        StandardWatchEventKinds.ENTRY_DELETE
                );
                watchedDirectories.put(key, dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void emit(Consumer<String> statusSink, String message) {
        if (statusSink != null) {
            statusSink.accept(message);
        }
    }

    @Override
    public void close() {
        stop();
        executorService.shutdownNow();
    }
}
