package com.backup.automation;

import org.junit.jupiter.api.Test;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BackupTaskGateTest {
    @Test void manualTasksAreExclusiveUntilCompletion() {
        var queue = new ArrayDeque<Runnable>();
        var gate = new BackupTaskGate(queue::add);
        var events = new ArrayList<String>();
        assertTrue(gate.submitManual(() -> events.add("backup")));
        assertFalse(gate.submitManual(() -> events.add("restore")));
        assertTrue(gate.isBusy());
        assertTrue(events.isEmpty());
        queue.remove().run();
        assertEquals(List.of("backup"), events);
        assertTrue(gate.isBusy());
        gate.complete();
        assertFalse(gate.isBusy());
        assertTrue(gate.submitManual(() -> events.add("restore")));
        queue.remove().run();
        assertEquals(List.of("backup", "restore"), events);
    }

    @Test void latestAutomaticEventRunsOnceAfterActiveTaskCompletes() {
        var queue = new ArrayDeque<Runnable>();
        var gate = new BackupTaskGate(queue::add);
        var events = new ArrayList<String>();
        gate.submitManual(() -> events.add("restore"));
        queue.remove().run();
        gate.submitAutomatic(() -> events.add("older schedule"));
        gate.submitAutomatic(() -> events.add("latest watch"));
        assertTrue(queue.isEmpty());
        gate.complete();
        assertTrue(gate.isBusy());
        assertFalse(gate.submitManual(() -> fail("manual overtook pending automatic task")));
        queue.remove().run();
        assertEquals(List.of("restore", "latest watch"), events);
        gate.complete();
        assertFalse(gate.isBusy());
        assertTrue(queue.isEmpty());
    }

    @Test void automaticEventDuringFollowupIsRetainedWithoutRetryingCompletedTasks() {
        var queue = new ArrayDeque<Runnable>();
        var gate = new BackupTaskGate(queue::add);
        var events = new ArrayList<String>();
        gate.submitAutomatic(() -> events.add("one"));
        queue.remove().run();
        gate.submitAutomatic(() -> events.add("two"));
        gate.complete();
        queue.remove().run();
        gate.submitAutomatic(() -> events.add("three"));
        gate.complete();
        queue.remove().run();
        gate.complete();
        assertEquals(List.of("one", "two", "three"), events);
        assertFalse(gate.isBusy());
        assertTrue(queue.isEmpty());
    }

    @Test void starterFailureReleasesGateAndDispatchesPendingEvent() {
        var queue = new ArrayDeque<Runnable>();
        var gate = new BackupTaskGate(queue::add);
        gate.submitManual(() -> { throw new IllegalStateException("failed to launch worker"); });
        var events = new ArrayList<String>();
        gate.submitAutomatic(() -> events.add("pending"));
        assertThrows(IllegalStateException.class, () -> queue.remove().run());
        queue.remove().run();
        assertEquals(List.of("pending"), events);
        gate.complete();
        assertFalse(gate.isBusy());
    }

    @Test void synchronousDispatcherFailureDoesNotUnlockActiveFollowup() {
        var gate = new BackupTaskGate(Runnable::run);
        var events = new ArrayList<String>();
        assertThrows(IllegalStateException.class, () -> gate.submitManual(() -> {
            gate.submitAutomatic(() -> events.add("followup started"));
            throw new IllegalStateException("starter failed");
        }));
        assertEquals(List.of("followup started"), events);
        assertTrue(gate.isBusy());
        assertFalse(gate.submitManual(() -> fail("followup overlapped")));
        gate.complete();
        assertFalse(gate.isBusy());
    }

    @Test void distinctAutomationSnapshotsSurviveWhileSameKeyCoalescesInOrder() {
        var queue = new ArrayDeque<Runnable>();
        var gate = new BackupTaskGate(queue::add);
        var events = new ArrayList<String>();
        gate.submitManual(() -> events.add("restore"));
        queue.remove().run();
        gate.submitAutomatic("watch B", () -> events.add("old B"));
        gate.submitAutomatic("schedule A", () -> events.add("A"));
        gate.submitAutomatic("watch B", () -> events.add("latest B"));
        gate.complete();
        queue.remove().run();
        gate.complete();
        queue.remove().run();
        gate.complete();
        assertEquals(List.of("restore", "latest B", "A"), events);
        assertFalse(gate.isBusy());
        assertTrue(queue.isEmpty());
    }

    @Test void cancelRemovesOnlyStoppedAutomationPendingWork() {
        var queue = new ArrayDeque<Runnable>();
        var gate = new BackupTaskGate(queue::add);
        var events = new ArrayList<String>();
        gate.submitManual(() -> events.add("restore"));
        queue.remove().run();
        gate.submitAutomatic("schedule", () -> events.add("schedule"));
        gate.submitAutomatic("watch", () -> fail("stopped watch ran"));
        gate.cancelAutomatic("watch");
        gate.complete();
        queue.remove().run();
        gate.complete();
        assertEquals(List.of("restore", "schedule"), events);
        assertFalse(gate.isBusy());
        assertTrue(queue.isEmpty());
    }

    @Test void closeDiscardsPendingAndRejectsFurtherWork() {
        var queue = new ArrayDeque<Runnable>();
        var gate = new BackupTaskGate(queue::add);
        gate.submitManual(() -> {});
        queue.remove().run();
        gate.submitAutomatic(() -> fail("closed pending task ran"));
        gate.close();
        gate.complete();
        assertFalse(gate.submitManual(() -> fail("closed manual ran")));
        gate.submitAutomatic(() -> fail("closed automatic ran"));
        assertFalse(gate.isBusy());
        assertTrue(queue.isEmpty());
    }
}
