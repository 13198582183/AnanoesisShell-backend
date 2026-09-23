package com.ananoesis.shell.ssh;

/**
 * 交互式终端的输出回调。
 *
 * <p>WHY 是回调接口而不是队列/流：输出要**实时**推到 WebSocket（spec「命令实时回显」），
 * 任何"先攒起来再取"的形态都会引入延迟与内存增长。回调让传输层只管产生事件，
 * 由上层决定怎么发（WebSocket 层转成契约的 {@code terminal_output}）。</p>
 *
 * <p><b>线程约定</b>：{@link #onStdout}/{@link #onStderr} 分别在各自的读线程上被调用，
 * {@link #onClosed} 可能在读线程或调用 {@code close()} 的线程上被调用。
 * 实现必须是线程安全的，且 MUST NOT 长时间阻塞——阻塞读线程等于阻塞 SSH 通道的窗口流控。</p>
 */
public interface TerminalOutputListener {

    /** 远端标准输出（PTY 下含回显与转义序列）。 */
    void onStdout(String data);

    /** 远端标准错误。PTY 下多数程序仍会与 stdout 合流，此回调主要服务于未合流的场景。 */
    void onStderr(String data);

    /**
     * 会话已结束，资源已释放。恰好回调一次。
     * WHY 由传输层回调而不是让上层轮询 {@code isOpen()}：
     * "远端关闭连接"是异步事件，只有回调能让前端立刻收到提示（spec「远端关闭连接」）。
     */
    void onClosed(SshCloseReason reason);
}
