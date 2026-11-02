package com.backup.automation;

import java.util.Objects;
import java.util.LinkedHashMap;
import java.util.concurrent.Executor;

/**
 * Serializes asynchronous backup/restore workers. The executor dispatches worker starters
 * (the UI supplies SwingUtilities::invokeLater); each worker must call complete exactly once.
 * While busy, each automation key retains one latest pending starter, in first-arrival order.
 * Completion consumes each pending starter once;
 * failures do not manufacture retries. Manual requests are rejected while busy.
 */
public final class BackupTaskGate implements AutoCloseable {
    private final Executor dispatcher;
    private boolean busy;
    private boolean closed;
    private static final Object DEFAULT_AUTOMATION_KEY = new Object();
    private final LinkedHashMap<Object, Runnable> pending = new LinkedHashMap<>();

    public BackupTaskGate(Executor dispatcher) {
        this.dispatcher = Objects.requireNonNull(dispatcher);
    }

    public synchronized boolean submitManual(Runnable starter) {
        Objects.requireNonNull(starter);
        if (closed || busy) return false;
        busy = true;
        dispatch(starter);
        return true;
    }

    public void submitAutomatic(Runnable starter) {
        submitAutomatic(DEFAULT_AUTOMATION_KEY, starter);
    }

    public synchronized void submitAutomatic(Object taskKey, Runnable starter) {
        Objects.requireNonNull(taskKey);
        Objects.requireNonNull(starter);
        if (closed) return;
        if (busy) pending.put(taskKey, starter);
        else {
            busy = true;
            dispatch(starter);
        }
    }

    /** Removes queued work for a stopped automation; an already running worker finishes normally. */
    public synchronized void cancelAutomatic(Object taskKey) {
        pending.remove(Objects.requireNonNull(taskKey));
    }

    public synchronized boolean isBusy() { return busy; }

    public synchronized void complete() {
        var next = pending.pollFirstEntry();
        if (closed || next == null) busy = false;
        else dispatch(next.getValue());
    }

    private void dispatch(Runnable starter) {
        boolean[] started = { false };
        try {
            dispatcher.execute(() -> {
                synchronized (this) {
                    started[0] = true;
                    if (closed) { busy = false; return; }
                }
                try { starter.run(); }
                catch (RuntimeException | Error failure) {
                    complete();
                    throw failure;
                }
            });
        } catch (RuntimeException | Error failure) {
            // An inline executor can propagate a starter failure after complete dispatched a successor.
            if (!started[0]) {
                busy = false;
                pending.clear();
            }
            throw failure;
        }
    }

    @Override public synchronized void close() {
        closed = true;
        pending.clear();
    }
}
