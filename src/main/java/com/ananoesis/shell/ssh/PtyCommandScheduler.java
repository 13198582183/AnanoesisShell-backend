package com.ananoesis.shell.ssh;

import java.util.LinkedList;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PTY 输入调度器（tasks 5.3 + 5.4）。
 *
 * <p>管理 Agent 与人工在共享 PTY 上的输入权。核心约束：</p>
 * <ul>
 *   <li>状态机：{@code manual_idle → agent_owned → manual_idle}（循环），
 *       以及 {@code manual_busy}（人工输入中）、{@code stopping}（正在停止）、
 *       {@code unknown}（无法判定）</li>
 *   <li>人工半行/粘贴/全屏/嵌套 Shell 时不注入 Agent 字节</li>
 *   <li>只有确认空提示符（{@code manual_idle}）才领取输入权</li>
 *   <li>串行执行：读到可靠完成帧（{@code CMD_END} + {@code PROMPT}）才允许下一个</li>
 *   <li>60 秒超时 + 64 KiB 采集上限（design.md D3 默认配置）</li>
 *   <li>超限继续排空、仅丢弃超额采集内容</li>
 *   <li>中断后 3 秒无完成证据 → {@code unknown}</li>
 * </ul>
 *
 * <p>WHY 串行而非并行：PTY 是共享的 stdin/stdout，并发写入会让命令输出交错，
 * 无法区分哪段输出属于哪条命令。串行保证每条命令的采集完整且可归属。</p>
 */
public class PtyCommandScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(PtyCommandScheduler.class);

    /** design.md D3：60 秒执行超时。 */
    static final long DEFAULT_TIMEOUT_MS = 60_000;

    /** design.md D3：中断后 3 秒无完成证据 → unknown。 */
    static final long DEFAULT_INTERRUPT_WATCH_MS = 3_000;

    /** design.md D3：64 KiB 单命令采集上限。 */
    static final int MAX_COLLECTION_BYTES = 65536;

    /** Ctrl-C 字符（ASCII ETX）。 */
    private static final String CTRL_C = "\u0003";

    /** 命令 id 生成器——单调递增，保证同一会话内不重复。 */
    private final AtomicLong commandIdCounter = new AtomicLong(0);

    // ==================================================================
    // 状态机
    // ==================================================================

    /**
     * 输入调度状态。
     *
     * <p>WHY 用枚举而非布尔标志：五个状态之间的转换规则复杂，
     * 用 switch 表达式比一堆 if-else 更安全、可读性更好。</p>
     */
    public enum State {
        /** 人工空闲——Agent 可领取输入权。 */
        MANUAL_IDLE,
        /** 人工忙碌——用户正在输入/粘贴/全屏程序，Agent 不得注入。 */
        MANUAL_BUSY,
        /** Agent 拥有输入权——正在执行命令，拒绝人工输入。 */
        AGENT_OWNED,
        /** 正在停止——不再接受新命令。 */
        STOPPING,
        /** 无法判定——钩子丢失/中断后无完成证据/嵌套 Shell 等。 */
        UNKNOWN
    }

    private volatile State state = State.MANUAL_IDLE;

    // ==================================================================
    // 依赖
    // ==================================================================

    private final SshTerminalSession terminalSession;
    private final String expectedNonce;
    private final ScheduledExecutorService scheduler;
    private final long timeoutMs;
    private final long interruptWatchMs;

    // ==================================================================
    // 运行时状态
    // ==================================================================

    /** 当前正在执行的命令（agent_owned 时非 null）。 */
    private PendingCommand currentCommand;

    /** 等待执行的命令队列。 */
    private final Queue<PendingCommand> commandQueue = new LinkedList<>();

    /** 超时定时任务（可取消）。 */
    private ScheduledFuture<?> pendingTimeout;

    /** 中断后观察窗口定时任务（可取消）。 */
    private ScheduledFuture<?> pendingWatch;

    /** 是否已收到 CMD_END（用于判断 PROMPT 是否标志着命令完成）。 */
    private boolean cmdEndReceived;

    /**
     * 会话级当前工作目录（最新一条 CWD 帧的路径，无论来自人工 cd 还是 Agent 命令）。
     *
     * <p>WHY 无条件更新而非只记在飞命令结果：浏览器验收发现用户 cd 后问 Agent
     * “当前目录”，旧逻辑丢弃 cmdId=0 的人工帧导致提示词只能注入空值，模型猜成 /。
     * volatile：帧处理在 WS 线程，读取在 Agent 工作线程。</p>
     */
    private volatile String sessionCwd;

    /**
     * 构造调度器（使用默认超时配置）。
     *
     * @param terminalSession 绑定的 PTY 会话
     * @param expectedNonce   Shell 集成的 nonce（用于帧过滤）
     * @param scheduler       超时调度用的线程池
     */
    public PtyCommandScheduler(SshTerminalSession terminalSession,
                               String expectedNonce,
                               ScheduledExecutorService scheduler) {
        this(terminalSession, expectedNonce, scheduler, DEFAULT_TIMEOUT_MS, DEFAULT_INTERRUPT_WATCH_MS);
    }

    /**
     * 构造调度器（可配置超时——供测试使用）。
     *
     * @param timeoutMs       命令执行超时（毫秒）
     * @param interruptWatchMs 中断后等待完成证据的窗口（毫秒）
     */
    public PtyCommandScheduler(SshTerminalSession terminalSession,
                               String expectedNonce,
                               ScheduledExecutorService scheduler,
                               long timeoutMs,
                               long interruptWatchMs) {
        this.terminalSession = Objects.requireNonNull(terminalSession, "terminalSession 不得为 null");
        this.expectedNonce = Objects.requireNonNull(expectedNonce, "expectedNonce 不得为 null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler 不得为 null");
        this.timeoutMs = timeoutMs;
        this.interruptWatchMs = interruptWatchMs;
    }

    // ==================================================================
    // 公开 API
    // ==================================================================

    /** 当前状态。 */
    public State state() {
        return state;
    }

    /** 排队等待的命令数。 */
    public synchronized int queuedCount() {
        return commandQueue.size();
    }

    /** 当前工作目录（最近一次 CWD 帧的值）。 */
    public synchronized String currentWorkingDirectory() {
        return currentCommand != null ? currentCommand.lastCwd : null;
    }

    // ==================================================================
    // 集成事件
    // ==================================================================

    /**
     * Shell 集成成功安装——进入 {@code manual_idle}。
     *
     * <p>WHY 需要显式调用而不是在构造器里自动设置：构造器在 SessionRuntime 初始化时调用，
     * 而集成安装是异步的（需要等 PTY 输出确认）。只有安装成功后才能开始调度。</p>
     */
    public void onIntegrationSuccess() {
        synchronized (this) {
            this.state = State.MANUAL_IDLE;
        }
        LOG.debug("Shell 集成成功，调度器进入 manual_idle");
    }

    /**
     * Shell 集成被跳过（不支持的 Shell）——进入 {@code unknown}。
     *
     * <p>WHY 是 unknown 而非 manual_idle：不支持的 Shell 没有帧协议，
     * 调度器无法识别命令边界，因此不能自动执行任何命令。</p>
     */
    public void onIntegrationSkipped() {
        synchronized (this) {
            this.state = State.UNKNOWN;
        }
        LOG.debug("Shell 集成被跳过，调度器进入 unknown");
    }

    // ==================================================================
    // 人工输入事件
    // ==================================================================

    /**
     * 检测到人工输入活动（半行/粘贴/全屏程序）。
     *
     * <p>仅在 {@code manual_idle} 时生效。{@code agent_owned} 期间的人工输入
     * 由停止/接管机制处理，不改变状态。</p>
     */
    public void onManualBusy() {
        synchronized (this) {
            if (state == State.MANUAL_IDLE) {
                state = State.MANUAL_BUSY;
            }
        }
    }

    /**
     * 人工输入活动结束（回到空提示符）。
     *
     * <p>仅在 {@code manual_busy} 时生效。恢复到 {@code manual_idle}，
     * Agent 可以再次领取输入权。</p>
     */
    public void onManualIdle() {
        synchronized (this) {
            if (state == State.MANUAL_BUSY) {
                state = State.MANUAL_IDLE;
                // WHY 尝试派发：可能有人工忙碌期间排队的命令等待执行
                tryDispatchNext();
            }
        }
    }

    /**
     * 正在停止——不再接受新命令。
     *
     * <p>如果有正在执行的命令，发送中断并取消超时。
     * 当前命令完成后（或超时后）状态不会回到 manual_idle。</p>
     */
    public void onStopping() {
        synchronized (this) {
            state = State.STOPPING;
            cancelTimeout();
            cancelWatch();
            if (currentCommand != null) {
                terminalSession.send(CTRL_C);
            }
            // 清空队列——不再执行排队的命令
            for (PendingCommand cmd : commandQueue) {
                cmd.future.completeExceptionally(
                        new IllegalStateException("调度器正在停止"));
            }
            commandQueue.clear();
        }
    }

    // ==================================================================
    // 命令提交
    // ==================================================================

    /**
     * 提交一条命令到调度器。
     *
     * <p>根据当前状态决定行为：</p>
     * <ul>
     *   <li>{@code manual_idle}：立即领取输入权，发送命令</li>
     *   <li>{@code agent_owned}：排队等待当前命令完成</li>
     *   <li>其他状态：拒绝，返回已完成的异常 future</li>
     * </ul>
     *
     * @param command 要执行的 shell 命令
     * @return 命令结果的 future；被拒绝时已完成异常
     */
    public CompletableFuture<CommandResult> submitCommand(String command) {
        Objects.requireNonNull(command, "command 不得为 null");
        if (command.isEmpty()) {
            return failedFuture(new IllegalArgumentException("命令不得为空"));
        }

        synchronized (this) {
            switch (state) {
                case MANUAL_IDLE:
                    return dispatchCommand(command);
                case AGENT_OWNED:
                    return enqueueCommand(command);
                default:
                    return failedFuture(new IllegalStateException(
                            "当前状态 " + state + " 不接受命令提交"));
            }
        }
    }

    // ==================================================================
    // 帧处理（由 ShellFrameDecoder 回调）
    // ==================================================================

    /**
     * 处理一个解析出的 Shell 帧。
     *
     * <p>由运行时在 ShellFrameDecoder 的帧回调中调用。
     * 调度器根据帧类型和当前状态更新命令执行状态。</p>
     *
     * @param frame 解析出的控制帧
     */
    public void onFrame(ShellFrame frame) {
        if (frame == null) {
            return;
        }

        synchronized (this) {
            switch (frame.type()) {
                case CMD_START:
                    handleCmdStart(frame);
                    break;
                case CMD_END:
                    handleCmdEnd(frame);
                    break;
                case CWD:
                    handleCwd(frame);
                    break;
                case PROMPT:
                    handlePrompt(frame);
                    break;
            }
        }
    }

    // ==================================================================
    // 输出采集
    // ==================================================================

    /**
     * 采集一段命令输出。
     *
     * <p>由运行时在收到 PTY 输出时调用。输出在 {@code agent_owned} 状态下
     * 被收集到当前命令的缓冲区中。超过 64 KiB 上限后丢弃超额内容，
     * 但标记 {@code truncated}。</p>
     *
     * <p>WHY 继续排空而不停止采集：PTY 是共享的，停止读取会让远端写满窗口
     * 阻塞，命令永远不结束。继续排空确保命令能正常完成。</p>
     *
     * @param data 一段终端输出（已经 ShellFrameDecoder 剥离控制帧后的干净文本）
     */
    public void collectOutput(String data) {
        if (data == null || data.isEmpty()) {
            return;
        }
        synchronized (this) {
            if (currentCommand != null && state == State.AGENT_OWNED) {
                currentCommand.appendOutput(data);
            }
        }
    }

    // ==================================================================
    // 内部：命令派发
    // ==================================================================

    private CompletableFuture<CommandResult> dispatchCommand(String command) {
        String cmdId = String.valueOf(commandIdCounter.incrementAndGet());
        PendingCommand pending = new PendingCommand(cmdId, command);
        this.currentCommand = pending;
        this.state = State.AGENT_OWNED;
        this.cmdEndReceived = false;

        // WHY 以换行结尾：PTY 的 stdin 模拟键盘输入，
        // 命令文本 + Enter 才会被 Shell 解析执行
        terminalSession.send(command + "\n");

        // 启动超时计时
        pendingTimeout = scheduler.schedule(
                this::onTimeout, timeoutMs, TimeUnit.MILLISECONDS);

        LOG.debug("命令已派发: cmdId={} command={}", cmdId, command);
        return pending.future;
    }

    private CompletableFuture<CommandResult> enqueueCommand(String command) {
        PendingCommand pending = new PendingCommand(
                String.valueOf(commandIdCounter.incrementAndGet()), command);
        commandQueue.add(pending);
        LOG.debug("命令已排队: cmdId={} queueSize={}", pending.commandId, commandQueue.size());
        return pending.future;
    }

    /** 尝试派发队列中的下一条命令（仅在 manual_idle 时生效）。 */
    private void tryDispatchNext() {
        if (state != State.MANUAL_IDLE || commandQueue.isEmpty()) {
            return;
        }
        PendingCommand next = commandQueue.poll();
        if (next != null) {
            dispatchCommand(next.commandText);
        }
    }

    // ==================================================================
    // 内部：帧处理
    // ==================================================================

    private void handleCmdStart(ShellFrame frame) {
        // WHY 仅记录日志：CMD_START 确认命令开始执行，
        // 但不改变状态机的转换——派发时已进入 agent_owned
        if (currentCommand != null) {
            LOG.trace("CMD_START: cmdId={}", frame.commandId());
        }
    }

    private void handleCmdEnd(ShellFrame frame) {
        if (currentCommand == null || state != State.AGENT_OWNED) {
            return;
        }
        // 解析退出码
        int exitCode;
        try {
            exitCode = Integer.parseInt(frame.payload());
        } catch (NumberFormatException e) {
            exitCode = ExecOutcome.EXIT_CODE_UNKNOWN;
        }
        currentCommand.exitCode = exitCode;
        cmdEndReceived = true;
        LOG.trace("CMD_END: cmdId={} exitCode={}", frame.commandId(), exitCode);
        // WHY 不在此处完成命令：还需等 PROMPT 帧确认 Shell 已回到提示符，
        // 否则下一条命令的输入可能与上一条的输出混在一起
    }

    private void handleCwd(ShellFrame frame) {
        // 会话级 cwd 无条件更新：人工 cd（cmdId=0）与 Agent 命令的帧都是真实状态
        this.sessionCwd = frame.payload();
        if (currentCommand != null) {
            currentCommand.lastCwd = frame.payload();
        }
    }

    /**
     * 会话当前工作目录（供 Agent 系统提示词注入）。
     *
     * @return 最新 CWD 帧的路径；尚未收到任何 cwd 帧时为 null
     */
    public String sessionCwd() {
        return sessionCwd;
    }

    private void handlePrompt(ShellFrame frame) {
        if (currentCommand == null) {
            // WHY 不是直接 return：接线后 PROMPT 是"人工活动结束、回到空提示符"的
            // 唯一证据——WS input 钩子把状态标成 busy 后，若无此路径则永久 busy，
            // 后续获准命令全被拒（Shell → Agent 交接失效的根因之一）
            if (state == State.MANUAL_BUSY) {
                state = State.MANUAL_IDLE;
                // 可能有人工忙碌期间排队的命令等待执行
                tryDispatchNext();
            }
            return;
        }

        if (state == State.AGENT_OWNED && cmdEndReceived) {
            // 命令正常完成：CMD_END + PROMPT 序列
            cancelTimeout();
            completeCurrentCommand(false, false);
            state = State.STOPPING.equals(state) ? State.STOPPING : State.MANUAL_IDLE;
            // WHY 在状态恢复后再派发下一条：保证串行
            tryDispatchNext();
        } else if (state == State.AGENT_OWNED && !cmdEndReceived) {
            // PROMPT 但没有 CMD_END：可能是命令被中断后的提示符
            // 如果我们在等待中断结果（有 pendingWatch），这算完成证据
            if (pendingWatch != null) {
                cancelWatch();
                if (currentCommand.exitCode == null) {
                    currentCommand.exitCode = ExecOutcome.EXIT_CODE_UNKNOWN;
                }
                completeCurrentCommand(false, true);
                state = State.MANUAL_IDLE;
                tryDispatchNext();
            }
        }
    }

    // ==================================================================
    // 内部：超时与中断
    // ==================================================================

    private void onTimeout() {
        synchronized (this) {
            if (currentCommand == null || state != State.AGENT_OWNED) {
                return;
            }
            LOG.warn("命令执行超时，发送中断: cmdId={}", currentCommand.commandId);
            // 发送 Ctrl-C 中断命令
            terminalSession.send(CTRL_C);
            currentCommand.timedOut = true;

            // 启动观察窗口：interruptWatchMs 内等待完成证据
            pendingWatch = scheduler.schedule(
                    this::onWatchExpired, interruptWatchMs, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * 观察窗口到期——中断后无完成证据，转为 unknown。
     *
     * <p>design.md D3：中断后 3 秒仍无完成证据则标记 unknown，
     * 停止自动推进，提示人工检查或重新连接。</p>
     */
    private void onWatchExpired() {
        synchronized (this) {
            if (currentCommand == null || state != State.AGENT_OWNED) {
                return; // 已在其他路径完成
            }
            LOG.warn("中断后观察窗口到期，无完成证据，转为 unknown");
            // 完成当前命令（标记超时 + 退出码未知）
            if (currentCommand.exitCode == null) {
                currentCommand.exitCode = ExecOutcome.EXIT_CODE_UNKNOWN;
            }
            completeCurrentCommand(true, true);
            state = State.UNKNOWN;

            // 清空队列——无法判定命令边界，不再自动执行
            for (PendingCommand cmd : commandQueue) {
                cmd.future.completeExceptionally(
                        new IllegalStateException("调度器状态为 unknown，无法继续执行"));
            }
            commandQueue.clear();
        }
    }

    private void completeCurrentCommand(boolean forceUnknown, boolean cancelTimers) {
        if (currentCommand == null) {
            return;
        }
        if (cancelTimers) {
            cancelTimeout();
            cancelWatch();
        }

        CommandResult result = new CommandResult(
                currentCommand.exitCode != null ? currentCommand.exitCode : ExecOutcome.EXIT_CODE_UNKNOWN,
                currentCommand.collectedOutput(),
                currentCommand.truncated,
                currentCommand.timedOut,
                currentCommand.lastCwd);

        currentCommand.future.complete(result);
        currentCommand = null;
        cmdEndReceived = false;
    }

    private void cancelTimeout() {
        if (pendingTimeout != null) {
            pendingTimeout.cancel(false);
            pendingTimeout = null;
        }
    }

    private void cancelWatch() {
        if (pendingWatch != null) {
            pendingWatch.cancel(false);
            pendingWatch = null;
        }
    }

    // ==================================================================
    // 结果类型
    // ==================================================================

    /**
     * 一条命令的执行结果。
     *
     * @param exitCode       远端退出码
     * @param stdout         采集的标准输出（已按上限截断）
     * @param truncated      输出是否因超过采集上限而被截断
     * @param timedOut       命令是否因超时被中断
     * @param workingDirectory 命令执行后的工作目录（可能为 null）
     */
    public record CommandResult(
            int exitCode,
            String stdout,
            boolean truncated,
            boolean timedOut,
            String workingDirectory) {

        public CommandResult {
            stdout = stdout == null ? "" : stdout;
        }
    }

    // ==================================================================
    // 内部：挂起的命令
    // ==================================================================

    /**
     * 正在执行或排队的命令。
     *
     * <p>WHY 不用 record：命令在执行过程中需要累积状态（输出、退出码、cwd），
     * 这些字段需要可变。</p>
     */
    private final class PendingCommand {
        final String commandId;
        final String commandText;
        final CompletableFuture<CommandResult> future = new CompletableFuture<>();

        // 累积状态
        final StringBuilder outputBuffer = new StringBuilder();
        int collectedBytes = 0;
        boolean truncated = false;
        boolean timedOut = false;
        Integer exitCode = null;
        String lastCwd = null;

        PendingCommand(String commandId, String commandText) {
            this.commandId = commandId;
            this.commandText = commandText;
        }

        /**
         * 追加一段输出到采集缓冲区。
         *
         * <p>超过 {@link #MAX_COLLECTION_BYTES} 后丢弃超额内容但标记截断。
         * WHY 继续排空：见 {@link #collectOutput} 的注释。</p>
         */
        void appendOutput(String data) {
            byte[] bytes = data.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            int room = MAX_COLLECTION_BYTES - collectedBytes;
            if (room > 0) {
                int take = Math.min(room, bytes.length);
                // 按字符追加（可能比字节少，但不会超过上限）
                outputBuffer.append(data, 0, Math.min(take, data.length()));
                collectedBytes += take;
            }
            if (collectedBytes >= MAX_COLLECTION_BYTES) {
                truncated = true;
            }
        }

        String collectedOutput() {
            return outputBuffer.toString();
        }
    }

    // ==================================================================
    // 工具方法
    // ==================================================================

    private static <T> CompletableFuture<T> failedFuture(Throwable error) {
        CompletableFuture<T> future = new CompletableFuture<>();
        future.completeExceptionally(error);
        return future;
    }
}
