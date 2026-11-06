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

/**
 * 基于 WatchService 的实时备份感知器。
 *
 * <p>三个算法点：① WatchService 不递归，启动时 walkFileTree 为每个子目录注册
 * CREATE/MODIFY/DELETE 并维护 key→目录反查表；② ENTRY_CREATE 且为新目录时立即
 * 递归注册，否则启动后新建目录内的变化会全部丢失；③ 防抖——事件只置脏标记并记时间戳，
 * 轮询循环发现"脏且静默期达到 debounce"才触发一次备份，吸收编辑器的临时文件+重命名事件风暴。</p>
 *
 * <p>线程模型：单守护线程 poll(500ms) 轮询而非 take() 阻塞——stop() 最多 500ms 生效；
 * 会话隔离：旧循环通过 watchService == session 判断自然退出，旧会话永不误杀新会话。</p>
 */
public final class WatchBackupService implements AutoCloseable {
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ExecutorService executorService = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "backup-watch-service");
        thread.setDaemon(true);
        return thread;
    });
    private volatile WatchService watchService;

    /** 启动监听会话：真实路径校验 → 递归注册 → 起轮询线程；启动失败时彻底清理会话再抛出。 */
    public synchronized void start(Path sourceDirectory, Duration debounce, Runnable backupTask, Consumer<String> statusSink) throws IOException {
        Objects.requireNonNull(sourceDirectory, "sourceDirectory");
        Objects.requireNonNull(debounce, "debounce");
        Objects.requireNonNull(backupTask, "backupTask");
        if (running.get()) throw new IllegalStateException("Watch backup is already running.");
        if (executorService.isShutdown()) throw new IllegalStateException("Watch service is closed.");
        Path root = sourceDirectory.toRealPath();
        if (!Files.isDirectory(root)) throw new IOException("Watch source is not a directory: " + root);
        WatchService session = root.getFileSystem().newWatchService();
        Map<WatchKey, Path> watchedDirectories = new HashMap<>();
        try {
            registerTree(root, watchedDirectories, session);
            watchService = session;
            running.set(true);
            executorService.submit(() -> watchLoop(root, watchedDirectories, debounce, backupTask, statusSink, session));
        } catch (IOException | RuntimeException e) {
            running.set(false);
            session.close();
            throw e;
        }
    }

    /** 会话是否仍在运行（旧会话退出不会影响新会话的该标志）。 */
    public boolean isRunning() {
        return running.get();
    }

    /** 停止：关 WatchService 触发 ClosedWatchServiceException 使轮询循环自然退出。 */
    public synchronized void stop() {
        running.set(false);
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException ignored) {
                // Closing a watcher is best effort during shutdown.
            }
        }
    }

    /** 轮询主循环：事件→置脏/动态注册；脏且静默期满→触发备份（经闸门合并）。 */
    private void watchLoop(
            Path sourceDirectory,
            Map<WatchKey, Path> watchedDirectories,
            Duration debounce,
            Runnable backupTask,
            Consumer<String> statusSink,
            WatchService session
    ) {
        long debounceMillis = Math.max(250, debounce.toMillis());
        // 防抖下限 250ms：防止用户配出「每个事件都触发」的极端行为。
        long lastChange = 0;
        boolean dirty = false;
        try {
            while (running.get() && watchService == session) {
                WatchKey key = session.poll(500, TimeUnit.MILLISECONDS);
                // 有事件：反查目录 → 遍历事件（OVERVIEW 溢出直接置脏）→ 新目录动态注册 → 置脏计时。
                // 有事件：反查目录 → 遍历事件（OVERFLOW 溢出直接置脏全量备份）→ 新目录动态注册 → 置脏计时。
    if (key != null) {
                    Path directory = watchedDirectories.get(key);
                    if (directory != null) {
                        for (WatchEvent<?> event : key.pollEvents()) {
                            WatchEvent.Kind<?> kind = event.kind();
                            if (kind == StandardWatchEventKinds.OVERFLOW) {
                                dirty = true;
                                lastChange = System.currentTimeMillis();
                                continue;
                            }
                            Path changed = directory.resolve((Path) event.context());
                            if (kind == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(changed, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                                try {
                                    registerTree(changed, watchedDirectories, session);
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
                // 防抖判定：静默期达到阈值才触发一次，且会话仍有效（防止旧会话触发新配置）。
                // 防抖判定：静默期达到阈值才触发一次，且会话仍有效（防止旧会话以新配置触发）。
    if (running.get() && watchService == session && dirty && System.currentTimeMillis() - lastChange >= debounceMillis) {
                    dirty = false;
                    emit(statusSink, "Change detected under " + sourceDirectory + "; running backup.");
                    backupTask.run();
                }
            }
        // 退出路径：中断/ClosedWatchService（stop 预期行为）/运行时异常——都保证关闭会话，
        // 且只有"当前会话"才把自己标记为停了（旧会话退出不能停掉新会话）。
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (java.nio.file.ClosedWatchServiceException ignored) {
            // Expected after stop; an old session must never stop a replacement session.
        } catch (RuntimeException e) {
            emit(statusSink, "Watch backup stopped: " + e.getMessage());
        } finally {
            synchronized (this) {
                if (watchService == session) running.set(false);
            }
            try { session.close(); } catch (IOException ignored) {}
        }
    }

    /** 递归注册整棵（子）树；key.reset() 失败的目录从反查表移除，防止无效键累积。 */
    private void registerTree(Path root, Map<WatchKey, Path> watchedDirectories, WatchService session) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                WatchKey key = dir.register(
                        session,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY,
                        StandardWatchEventKinds.ENTRY_DELETE
                );
                watchedDirectories.put(key, dir);
        // 注册即记录 key→目录映射：事件到来时才知道是哪个目录变了。
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** 状态回调：空安全（sink 可为 null），报告动态注册失败等非致命事件。 */
    private void emit(Consumer<String> statusSink, String message) {
        if (statusSink != null) {
            statusSink.accept(message);
        }
    }

    @Override
    /** 停止 + 停线程池；AutoCloseable 供窗口关闭钩子使用。 */
    public void close() {
        stop();
        executorService.shutdownNow();
    }
}
