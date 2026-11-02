package com.backup.automation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class AutomationServicesTest {
    @TempDir Path temp;

    @Test void failedWatchStartDoesNotLeaveServiceRunning() throws Exception {
        try (WatchBackupService watcher = new WatchBackupService()) {
            assertThrows(java.io.IOException.class, () -> watcher.start(temp.resolve("missing"), Duration.ofMillis(250), () -> {}, null));
            assertFalse(watcher.isRunning());
            watcher.start(temp, Duration.ofMillis(250), () -> {}, null);
            assertTrue(watcher.isRunning());
        }
    }

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
