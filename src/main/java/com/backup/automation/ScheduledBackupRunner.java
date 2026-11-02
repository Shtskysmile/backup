package com.backup.automation;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class ScheduledBackupRunner implements AutoCloseable {
    private final ScheduledExecutorService executorService = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "backup-scheduled-backup");
        thread.setDaemon(true);
        return thread;
    });
    private ScheduledFuture<?> scheduledFuture;

    public synchronized void start(Duration interval, Runnable backupTask) {
        Objects.requireNonNull(interval, "interval");
        Objects.requireNonNull(backupTask, "backupTask");
        if (!interval.isPositive()) {
            throw new IllegalArgumentException("Schedule interval must be positive.");
        }
        stop();
        long seconds = Math.max(1, interval.toSeconds());
        scheduledFuture = executorService.scheduleAtFixedRate(backupTask, seconds, seconds, TimeUnit.SECONDS);
    }

    public synchronized boolean isRunning() {
        return scheduledFuture != null && !scheduledFuture.isCancelled();
    }

    public synchronized void stop() {
        if (scheduledFuture != null) {
            scheduledFuture.cancel(false);
            scheduledFuture = null;
        }
    }

    @Override
    public synchronized void close() {
        stop();
        executorService.shutdownNow();
    }
}
