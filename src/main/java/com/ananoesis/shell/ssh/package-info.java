/**
 * SSH 连接与双通道执行层（tasks 6.1–6.6；design.md D4/D6/D7）。
 *
 * <p>职责：把 {@code hosts} 表里的一行配置，变成一条可用的 SSH 连接，
 * 并在其上提供两种截然不同的执行语义——</p>
 * <ul>
 *   <li><b>交互式 PTY 通道</b>（{@link com.ananoesis.shell.ssh.SshTerminalService}）：
 *       长生命周期、双向流式、需要心跳与空闲回收，供人使用；</li>
 *   <li><b>独立 exec 通道</b>（{@link com.ananoesis.shell.ssh.SshExecService}）：
 *       短生命周期、一次性、非交互（刻意不分配 PTY），三流分离且输出有界，
 *       供 AI 的 {@code run_command} 工具使用。</li>
 * </ul>
 *
 * <p>WHY 两者必须是两条通道而非复用同一条 shell：design.md D4 的核心裁定。
 * AI 若往交互式 shell 里灌命令，输出会与用户的提示符、历史回显混在一起，
 * 既无法可靠界定"哪段是这条命令的输出"，也拿不到退出码——工具调用会退化成猜测。</p>
 *
 * <p>安全纪律（credential-store spec）：</p>
 * <ul>
 *   <li>明文凭据只允许以 {@link com.ananoesis.shell.security.SecretText} 形态存在，
 *       且只能由 {@link com.ananoesis.shell.ssh.SshTargetResolver#withTarget} 在回调内取出；
 *       擦除动作焊死在其 {@code finally} 里，调用方无法"忘记"；</li>
 *   <li>私钥永不落盘：解析走 {@code Reader}（内存态）而非 {@code File}；</li>
 *   <li>对外错误一律经 {@link com.ananoesis.shell.ssh.SshConnectException} 归类为
 *       契约里的错误码，不透出远端 banner、密钥指纹或栈细节。</li>
 * </ul>
 *
 * <p>已知缺口：主机密钥尚未校验（accept-all + 首次告警），因为契约与 {@code hosts} 表
 * 都没有存放指纹的字段。TOFU/known_hosts 需要一次数据库迁移，待架构师裁定。</p>
 */
package com.ananoesis.shell.ssh;
