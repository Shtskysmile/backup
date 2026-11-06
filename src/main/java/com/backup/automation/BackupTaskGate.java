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
/**
 * 异步备份/还原任务的串行闸门：同一时刻至多一个 worker 在跑，杜绝并发写同一目录。
 *
 * <p>状态机：idle →(submit*)→ busy →(complete 且无 pending)→ idle；busy 期间
 * 手动请求被拒绝（UI 弹窗），自动请求按任务 key 合并——pending LinkedHashMap 每个
 * key 只保留最新 starter（先到先序），complete 时按序消费。停止自动化时
 * cancelAutomatic 移除其待执行请求；正在运行的 worker 不受影响。</p>
 *
 * <p>竞态处理：dispatch 里用 started 标志区分"executor 已受理"与"提交即失败"，
 * 失败路径回滚 busy 并清空 pending，且绝不重复 complete——这是双 complete
 * 问题的根源防线（有专项测试覆盖）。</p>
 */
public final class BackupTaskGate implements AutoCloseable {
    private final Executor dispatcher;
    private boolean busy;
    private boolean closed;
    private static final Object DEFAULT_AUTOMATION_KEY = new Object();
    // 默认 key：单自动化任务场景的兼容入口（定时/实时各自带独立 key）。
    private final LinkedHashMap<Object, Runnable> pending = new LinkedHashMap<>();
    // pending：插入序保持先到先服务；同 key 覆盖即「只保留最新意图」。

    /** dispatcher 决定 starter 在哪个线程执行（UI 传 SwingUtilities::invokeLater；测试传内联 executor）。 */
    public BackupTaskGate(Executor dispatcher) {
        this.dispatcher = Objects.requireNonNull(dispatcher);
    }

    /** 手动请求：忙或已关闭时返回 false（调用方弹窗提示），空闲则占用闸门并派发。 */
    public synchronized boolean submitManual(Runnable starter) {
        Objects.requireNonNull(starter);
        if (closed || busy) return false;
        busy = true;
        dispatch(starter);
        return true;
    }

    /** 过载兼容入口：默认任务 key（单自动化任务场景）。 */
    public void submitAutomatic(Runnable starter) {
        submitAutomatic(DEFAULT_AUTOMATION_KEY, starter);
    }

    /** 自动请求：忙时合并到 pending（同 key 覆盖旧的 = 只保留最新意图），空闲即派发。 */
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

    /** 闸门状态查询（UI 提示与测试断言用）。 */
    public synchronized boolean isBusy() { return busy; }

    /** worker 完成时必须恰好调用一次：有 pending 则接力派发（busy 保持），否则释放闸门。 */
    public synchronized void complete() {
        var next = pending.pollFirstEntry();
        // 接力语义：pending 有货 → busy 保持并派发下一个；否则才真正释放闸门。
        if (closed || next == null) busy = false;
        else dispatch(next.getValue());
    }

    /** 经由 dispatcher（UI 传 invokeLater）把 starter 调回 EDT；提交失败时回滚状态并清除所有 pending。 */
    private void dispatch(Runnable starter) {
        boolean[] started = { false };
        try {
            dispatcher.execute(() -> {
        // dispatch 竞态防护：started 标志区分「已受理」与「提交即失败」，防双 complete。
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

    /** 关闭：closed 标志令所有后续提交失效；已派发的 starter 在 dispatch 里自行退避。 */
    @Override public synchronized void close() {
        closed = true;
        pending.clear();
    }
}
