package com.ananoesis.shell.ssh;

/**
 * 会话通道类型，对应 {@code sessions.session_type} 的 CHECK 取值（design.md D4 双通道）。
 *
 * <p>WHY 必须区分：审计要能回答"这条命令是人敲的还是 AI 发起的"。
 * 两种通道的生命周期与风险等级完全不同——交互式 PTY 可能持续数小时，
 * exec 则是秒级的一次性调用。</p>
 */
public enum SessionKind {

    /** 交互式 PTY shell（人用的终端，通道 1）。 */
    INTERACTIVE_PTY("interactive_pty"),

    /** 独立 exec 通道（AI 的 {@code run_command} 等，通道 2）。 */
    EXEC("exec");

    private final String columnValue;

    SessionKind(String columnValue) {
        this.columnValue = columnValue;
    }

    public String columnValue() {
        return columnValue;
    }
}
