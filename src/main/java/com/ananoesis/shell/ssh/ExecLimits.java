package com.ananoesis.shell.ssh;

import java.time.Duration;
import java.util.Objects;

import com.ananoesis.shell.config.SshProperties;

/**
 * 单次 exec 的约束（design.md D4：执行超时 + 输出长度上限 + 非交互）。
 *
 * <p>WHY 每次调用可覆盖而不是全局固定：
 * 只读工具（{@code list_dir}/{@code read_file}）应当快进快出、输出小；
 * 而 {@code run_command} 可能是用户批准的一次重启操作，需要更长的时限。
 * 把约束做成参数，Wave 3 就能按工具分级施加不同策略，而不必改动本层。</p>
 *
 * @param timeout        执行时限；超时则中断命令并在结果里标记 {@code timedOut}
 * @param maxOutputBytes stdout/stderr 各自的采集上限（字节）
 */
public record ExecLimits(Duration timeout, int maxOutputBytes) {

    public ExecLimits {
        Objects.requireNonNull(timeout, "timeout 不得为 null");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout 必须为正数，实际为 " + timeout);
        }
        if (maxOutputBytes <= 0) {
            throw new IllegalArgumentException("maxOutputBytes 必须为正数，实际为 " + maxOutputBytes);
        }
    }

    /** 取自配置的默认约束。 */
    public static ExecLimits from(SshProperties properties) {
        return new ExecLimits(properties.getExecTimeout(), properties.getExecMaxOutputBytes());
    }
}
