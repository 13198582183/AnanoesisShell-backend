package com.ananoesis.shell.ssh;

import java.util.Objects;

/**
 * 预安装静音监听器：bash 会话从<b>第一个输出字节</b>起吞掉 stdout/stderr，
 * 直到 {@link SshTerminalService} 把输出链切给安装闸门（known-issues #19）。
 *
 * <h2>WHY 需要它（闸门之上再垫一层）</h2>
 * <p>{@link ShellIntegrationInstaller} 的闸门只吞「写安装代码之后」的回显噪声，
 * 但闸门接入本身有窗口：{@code createTerminal} 的读泵在 startShell 返回时就开始
 * 回调，而 {@code switchTo(gate)} 要等主线程走到 installer 才发生。真实 bash 登录
 * 后<b>不等任何输入</b>立刻打印 banner + 首个 prompt——首连握手最慢、这些字节到达
 * 最早，可能抢在闸门接入前经原监听器直漏前端。本类把保护起点提前到会话第一个
 * 字节，与闸门无缝衔接，彻底封死这个竞态窗口。</p>
 *
 * <p>注：用户报告的「首连同一行双提示符」真根因不在此（是旧三段式安装分次发送在
 * 开闸后各弹一个 prompt，已由 {@link ShellIntegration#install(String)} 单行合并修复，
 * 见 known-issues #19），但 banner 泄漏窗口真实存在，本静音作为竞态防御保留。</p>
 *
 * <h2>边界</h2>
 * <ul>
 *   <li>仅 bash 会话挂载（非 bash 不装静音也不装闸门，登录 banner 照常透传，
 *       design D3 降级承诺）</li>
 *   <li>{@link #onClosed} <b>MUST 透传</b>：输出可以静音，会话终结不行——
 *       静音窗口内远端断开/挂断，前端必须立刻收到提示</li>
 *   <li>生命周期极短（毫秒级，到 installer 的 switchTo 即被替换）；即便替换前
 *       读泵仍持有本引用，吞的也只是安装瞬间的噪声，语义不变</li>
 * </ul>
 */
final class PreInstallMuteListener implements TerminalOutputListener {

    private final TerminalOutputListener delegate;

    PreInstallMuteListener(TerminalOutputListener delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate 不得为 null");
    }

    /** 被保护的下游监听器（接线方兜底切回时使用）。 */
    TerminalOutputListener delegate() {
        return delegate;
    }

    @Override
    public void onStdout(String data) {
        // 故意吞掉：登录 banner 与首 prompt 的泄漏源头（#19 竞态防御），无日志——首连高频路径
    }

    @Override
    public void onStderr(String data) {
        // 同上：PTY 下 stderr 与 stdout 合流，一并静音
    }

    @Override
    public void onClosed(SshCloseReason reason) {
        delegate.onClosed(reason);
    }
}
