package com.ananoesis.shell.support;

import java.time.Duration;
import java.util.function.BooleanSupplier;

/**
 * 轮询等待辅助：把"异步事件最终会发生"写成可读且不脆弱的断言。
 *
 * <p>WHY 不用 {@code Thread.sleep(固定时长)}：SSH 输出经网络栈与两条读线程到达，
 * 在负载较高的 CI 上固定睡眠既可能不够（偶发红），又必然拖慢每次构建。
 * 轮询到条件成立即返回，最坏情况才等满超时。</p>
 *
 * <p>WHY 不用 Awaitility：本项目测试坚持"手写替身优先、少引框架"，
 * 二十行的轮询不值得换来一个额外依赖与一套需要学习的 DSL。</p>
 */
public final class TestWait {

    /** 常规等待上限：本机回环下事件通常在几十毫秒内到达。 */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    private static final long POLL_INTERVAL_MILLIS = 20L;

    private TestWait() {
    }

    public static void until(String description, BooleanSupplier condition) {
        until(description, DEFAULT_TIMEOUT, condition);
    }

    /**
     * 轮询直到条件成立。
     *
     * @param description 失败时出现在断言消息里的说明，必须能让读者知道"在等什么"
     * @throws AssertionError 超时仍未成立
     */
    public static void until(String description, Duration timeout, BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        AssertionError lastFailure = null;
        while (System.nanoTime() < deadline) {
            try {
                if (condition.getAsBoolean()) {
                    return;
                }
            } catch (RuntimeException e) {
                // WHY 记住而不立即抛出：被等待的对象可能正处于"刚被置空"的中间态，
                // 例如会话关闭瞬间读取其状态。若首次异常就失败，测试会变得抖动。
                lastFailure = new AssertionError("等待期间出现异常: " + e, e);
            }
            sleep(POLL_INTERVAL_MILLIS);
        }
        throw new AssertionError("超时（" + timeout.toMillis() + "ms）仍未满足: " + description, lastFailure);
    }

    /** 等待一段固定时间；仅用于"必须给对端留出反应时间"的少数场景。 */
    public static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待被中断", e);
        }
    }
}
