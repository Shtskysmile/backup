package com.backup.automation;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 固定频率定时备份调度器。
 *
 * <p>设计要点：单线程 ScheduledExecutorService（守护线程，命名便于线程转储识别）；
 * start 内先 stop，重复启动等价于改周期，绝不出现双调度；取消用 cancel(false)——
 * 让正在写归档的任务跑完，中断写一半的归档等于制造损坏文件。</p>
 */
public final class ScheduledBackupRunner implements AutoCloseable {
    private final ScheduledExecutorService executorService = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "backup-scheduled-backup");
        thread.setDaemon(true);
        return thread;
    });
    private ScheduledFuture<?> scheduledFuture;

    /** 以固定频率启动：内部先 stop，重复启动等价于改周期；周期下限 1 秒。 */
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

    /** 运行状态查询（UI 按钮态切换用）。 */
    public synchronized boolean isRunning() {
        return scheduledFuture != null && !scheduledFuture.isCancelled();
    }

        /** 停止接收新触发；正在执行的任务允许跑完（cancel(false) 的语义选择）。 */
    public synchronized void stop() {
        if (scheduledFuture != null) {
            scheduledFuture.cancel(false);
            scheduledFuture = null;
        }
    }

    @Override
    /** 彻底关闭：停触发 + 停线程池，窗口关闭时调用，进程可正常退出。 */
    public synchronized void close() {
        stop();
        executorService.shutdownNow();
    }
}
