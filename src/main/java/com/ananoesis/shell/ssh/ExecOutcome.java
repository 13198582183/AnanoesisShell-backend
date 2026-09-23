package com.ananoesis.shell.ssh;

/**
 * exec 通道一次命令执行的结果（task 6.5）。
 *
 * <p>这是**为 Wave 3 预留的稳定接口**：AI 工具 {@code run_command} 与只读工具
 * （{@code list_dir}/{@code read_file}/{@code system_info}）都将只经由
 * {@link SshExecService} 拿到本记录，再映射为契约的 {@code ToolCallEvent}。映射约定：</p>
 * <ul>
 *   <li>{@code timedOut=true} → {@code result_status=execution_timeout}</li>
 *   <li>{@code truncated=true} → {@code result_status=output_truncated}</li>
 *   <li>{@code exitCode != 0} → {@code result_status=failed}（或按上层策略 {@code error}）</li>
 *   <li>其余 → {@code result_status=success}</li>
 * </ul>
 *
 * <p>WHY 用 record：结果是不可变的事实，不需要 setter；
 * 且 {@code toString()} 天然可用于日志（内容不含凭据）。</p>
 *
 * @param exitCode       远端退出码；命令未正常退出（超时/中断）时为 {@code -1}
 * @param stdout         标准输出，已按上限截断
 * @param stderr         标准错误，已按上限截断
 * @param truncated      是否因超过 {@link ExecLimits#maxOutputBytes()} 而被截断
 * @param timedOut       是否因超过 {@link ExecLimits#timeout()} 而被中断
 * @param durationMillis 本次执行耗时（含连接建立），用于审计与性能排查
 */
public record ExecOutcome(int exitCode, String stdout, String stderr,
                          boolean truncated, boolean timedOut, long durationMillis) {

    /** 命令未正常结束时的退出码占位值。WHY 是 -1：任何真实 shell 的退出码都在 0..255。 */
    public static final int EXIT_CODE_UNKNOWN = -1;

    public ExecOutcome {
        stdout = stdout == null ? "" : stdout;
        stderr = stderr == null ? "" : stderr;
    }

    public boolean isSuccess() {
        return !timedOut && exitCode == 0;
    }
}
