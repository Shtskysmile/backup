package com.backup.automation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 自动化集成测试：定时调度真触发一次、实时监听真改文件触发一次、
 * 监听重启后旧会话不干扰新会话。所有等待都基于轮询超时而非固定睡眠。
 *
 * 用例清单（3 项）：
 * - failedWatchStartDoesNotLeaveServiceRunning
 * - watcherDetectsChangeAfterRestart
 * - schedulerRunsThenStops
 */
class AutomationServicesTest {
    @TempDir Path temp;

    /** 用例 failedWatchStartDoesNotLeaveServiceRunning：自动化（定时触发/实时监听/会话恢复） 的具体场景，断言即预期行为。 */
    @Test void failedWatchStartDoesNotLeaveServiceRunning() throws Exception {
        try (WatchBackupService watcher = new WatchBackupService()) {
            assertThrows(java.io.IOException.class, () -> watcher.start(temp.resolve("missing"), Duration.ofMillis(250), () -> {}, null));
            assertFalse(watcher.isRunning());
            watcher.start(temp, Duration.ofMillis(250), () -> {}, null);
            assertTrue(watcher.isRunning());
        }
    }

    /** 用例 watcherDetectsChangeAfterRestart：自动化（定时触发/实时监听/会话恢复） 的具体场景，断言即预期行为。 */
    @Test void watcherDetectsChangeAfterRestart() throws Exception {
        CountDownLatch changed = new CountDownLatch(1);
        try (WatchBackupService watcher = new WatchBackupService()) {
            watcher.start(temp, Duration.ofMillis(250), () -> {}, null);
            watcher.stop();
            watcher.start(temp, Duration.ofMillis(250), changed::countDown, null);
            Files.writeString(temp.resolve("file"), "changed");
            assertTrue(changed.await(20, TimeUnit.SECONDS), "Filesystem change did not trigger the restarted watcher");
        }
    }

    /** 用例 schedulerRunsThenStops：自动化（定时触发/实时监听/会话恢复） 的具体场景，断言即预期行为。 */
    @Test void schedulerRunsThenStops() throws Exception {
        CountDownLatch fired = new CountDownLatch(1);
        try (ScheduledBackupRunner runner = new ScheduledBackupRunner()) {
            runner.start(Duration.ofSeconds(1), fired::countDown);
            assertTrue(fired.await(5, TimeUnit.SECONDS));
            assertTrue(runner.isRunning());
            runner.stop();
            assertFalse(runner.isRunning());
        }
    }
}
