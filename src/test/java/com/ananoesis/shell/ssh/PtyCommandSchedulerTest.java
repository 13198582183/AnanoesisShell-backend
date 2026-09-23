package com.ananoesis.shell.ssh;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * PtyCommandScheduler 测试（tasks 5.3 + 5.4）。
 *
 * <p>验证输入状态机、串行执行、超时与采集上限、中断后 unknown 转换。</p>
 */
@DisplayName("PtyCommandScheduler")
class PtyCommandSchedulerTest {

    private static final String NONCE = "test-nonce-12345";

    private CapturingTerminalSession terminal;
    private ScheduledExecutorService executor;

    @BeforeEach
    void setUp() {
        terminal = new CapturingTerminalSession();
        executor = Executors.newScheduledThreadPool(2);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    /** 创建默认配置的调度器（短超时便于测试）。 */
    private PtyCommandScheduler createScheduler() {
        return new PtyCommandScheduler(terminal, NONCE, executor, 200, 150);
    }

    /** 创建自定义超时配置的调度器。 */
    private PtyCommandScheduler createScheduler(long timeoutMs, long watchMs) {
        return new PtyCommandScheduler(terminal, NONCE, executor, timeoutMs, watchMs);
    }

    // ==================================================================
    // 状态机
    // ==================================================================

    @Nested
    @DisplayName("状态机")
    class StateMachine {

        @Test
        @DisplayName("初始状态为 manual_idle（集成成功后）")
        void initialStateIsManualIdle() {
            PtyCommandScheduler scheduler = createScheduler();
            scheduler.onIntegrationSuccess();

            assertThat(scheduler.state()).isEqualTo(PtyCommandScheduler.State.MANUAL_IDLE);
        }

        @Test
        @DisplayName("集成未成功时状态为 unknown")
        void stateIsUnknownWhenIntegrationFailed() {
            PtyCommandScheduler scheduler = createScheduler();
            scheduler.onIntegrationSkipped();

            assertThat(scheduler.state()).isEqualTo(PtyCommandScheduler.State.UNKNOWN);
        }

        @Test
        @DisplayName("提交命令后状态变为 agent_owned")
        void submitCommandChangesStateToAgentOwned() {
            PtyCommandScheduler scheduler = createScheduler();
            scheduler.onIntegrationSuccess();

            scheduler.submitCommand("echo hello");

            assertThat(scheduler.state()).isEqualTo(PtyCommandScheduler.State.AGENT_OWNED);
        }

        @Test
        @DisplayName("命令完成后状态回到 manual_idle")
        void commandCompleteReturnsToManualIdle() {
            PtyCommandScheduler scheduler = createScheduler();
            scheduler.onIntegrationSuccess();

            scheduler.submitCommand("echo hello");
            scheduler.onFrame(new ShellFrame(ShellFrameType.CMD_END, NONCE, "cmd1", "0"));
            scheduler.onFrame(new ShellFrame(ShellFrameType.PROMPT, NONCE, "cmd1", ""));

            assertThat(scheduler.state()).isEqualTo(PtyCommandScheduler.State.MANUAL_IDLE);
        }

        @Test
        @DisplayName("人工 cd 的 cwd 帧（无在飞命令）也更新会话级 sessionCwd：Agent 提示词需要知道用户切到了哪里")
        void manualCwdFrameUpdatesSessionCwd() {
            // 浏览器验收发现：用户 cd /tmp/acceptance 后问 Agent“列出当前目录”，
            // 模型不知道 cwd 只能猜 /。旧实现 handleCwd 只在 currentCommand != null
            // 时记录（agent 命令结果），人工 cd 的 cmdId=0 帧被丢弃
            PtyCommandScheduler scheduler = createScheduler();
            scheduler.onIntegrationSuccess();
            assertThat(scheduler.sessionCwd()).isNull();

            scheduler.onFrame(new ShellFrame(ShellFrameType.CWD, NONCE, "0", "/tmp/acceptance"));

            assertThat(scheduler.sessionCwd()).isEqualTo("/tmp/acceptance");

            // 后续 agent 命令的 cwd 帧（cmdId 非 0）同样更新，不互相覆盖出旧值
            scheduler.submitCommand("pwd");
            scheduler.onFrame(new ShellFrame(ShellFrameType.CWD, NONCE, "c1", "/var/log"));
            assertThat(scheduler.sessionCwd()).isEqualTo("/var/log");
        }

        @Test
        @DisplayName("manual_busy 时提交命令被拒绝")
        void submitRejectedWhenManualBusy() {
            PtyCommandScheduler scheduler = createScheduler();
            scheduler.onIntegrationSuccess();
            scheduler.onManualBusy();

            CompletableFuture<PtyCommandScheduler.CommandResult> future =
                    scheduler.submitCommand("echo hello");

            assertThat(future).isCompletedExceptionally();
        }

        @Test
        @DisplayName("agent_owned 时提交命令被排队")
        void submitQueuedWhenAgentOwned() throws Exception {
            PtyCommandScheduler scheduler = createScheduler();
            scheduler.onIntegrationSuccess();

            scheduler.submitCommand("first");
            CompletableFuture<PtyCommandScheduler.CommandResult> second =
                    scheduler.submitCommand("second");

            // 第二条被排队，尚未完成
            assertThat(second.isDone()).isFalse();
            assertThat(scheduler.queuedCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("stopping 状态时提交命令被拒绝")
        void submitRejectedWhenStopping() {
            PtyCommandScheduler scheduler = createScheduler();
            scheduler.onIntegrationSuccess();
            scheduler.onStopping();

            CompletableFuture<PtyCommandScheduler.CommandResult> future =
                    scheduler.submitCommand("echo hello");

            assertThat(future).isCompletedExceptionally();
        }

        @Test
        @DisplayName("unknown 状态时提交命令被拒绝")
        void submitRejectedWhenUnknown() {
            PtyCommandScheduler scheduler = createScheduler();
            scheduler.onIntegrationSkipped(); // → unknown

            CompletableFuture<PtyCommandScheduler.CommandResult> future =
                    scheduler.submitCommand("echo hello");

            assertThat(future).isCompletedExceptionally();
        }
    }

    // ==================================================================
    // 串行执行
    // ==================================================================

    @Nested
    @DisplayName("串行执行")
    class SerialExecution {

        @Test
        @DisplayName("命令写入 PTY（以换行结尾）")
        void commandWrittenToPty() {
            PtyCommandScheduler scheduler = createScheduler();
            scheduler.onIntegrationSuccess();

            scheduler.submitCommand("echo hello");

            assertThat(terminal.sentData).contains("echo hello\n");
        }

        @Test
        @DisplayName("只有收到完成帧后才允许下一条命令")
        void nextCommandOnlyAfterCompletionFrame() throws Exception {
            PtyCommandScheduler scheduler = createScheduler();
            scheduler.onIntegrationSuccess();

            scheduler.submitCommand("first");
            scheduler.submitCommand("second");

            // 此时只应发送第一条
            assertThat(terminal.sentData).contains("first\n");
            assertThat(terminal.sentData).doesNotContain("second\n");

            // 完成第一条
            scheduler.onFrame(new ShellFrame(ShellFrameType.CMD_END, NONCE, "c1", "0"));
            scheduler.onFrame(new ShellFrame(ShellFrameType.PROMPT, NONCE, "c1", ""));

            // 现在第二条应该被发送
            assertThat(terminal.sentData).contains("second\n");
        }

        @Test
        @DisplayName("串行执行三条命令")
        void serialExecutionOfThreeCommands() throws Exception {
            PtyCommandScheduler scheduler = createScheduler();
            scheduler.onIntegrationSuccess();

            CompletableFuture<PtyCommandScheduler.CommandResult> f1 = scheduler.submitCommand("a");
            CompletableFuture<PtyCommandScheduler.CommandResult> f2 = scheduler.submitCommand("b");
            CompletableFuture<PtyCommandScheduler.CommandResult> f3 = scheduler.submitCommand("c");

            // 只发送了第一条
            assertThat(terminal.sentData).contains("a\n");
            assertThat(terminal.sentData).doesNotContain("b\n");

            // 完成第一条
            scheduler.onFrame(new ShellFrame(ShellFrameType.CMD_END, NONCE, "c1", "0"));
            scheduler.onFrame(new ShellFrame(ShellFrameType.PROMPT, NONCE, "c1", ""));

            // 第二条被发送
            assertThat(terminal.sentData).contains("b\n");

            // 完成第二条
            scheduler.onFrame(new ShellFrame(ShellFrameType.CMD_END, NONCE, "c2", "0"));
            scheduler.onFrame(new ShellFrame(ShellFrameType.PROMPT, NONCE, "c2", ""));

            // 第三条被发送
            assertThat(terminal.sentData).contains("c\n");
        }

        @Test
        @DisplayName("完成帧携带退出码被正确记录")
        void exitCodeRecordedFromCompletionFrame() throws Exception {
            PtyCommandScheduler scheduler = createScheduler();
            scheduler.onIntegrationSuccess();

            CompletableFuture<PtyCommandScheduler.CommandResult> future =
                    scheduler.submitCommand("exit 42");

            scheduler.onFrame(new ShellFrame(ShellFrameType.CMD_END, NONCE, "c1", "42"));
            scheduler.onFrame(new ShellFrame(ShellFrameType.PROMPT, NONCE, "c1", ""));

            PtyCommandScheduler.CommandResult result = future.get(1, TimeUnit.SECONDS);
            assertThat(result.exitCode()).isEqualTo(42);
        }

        @Test
        @DisplayName("CWD 帧被记录")
        void cwdFrameRecorded() throws Exception {
            PtyCommandScheduler scheduler = createScheduler();
            scheduler.onIntegrationSuccess();

            CompletableFuture<PtyCommandScheduler.CommandResult> future =
                    scheduler.submitCommand("cd /tmp");

            scheduler.onFrame(new ShellFrame(ShellFrameType.CWD, NONCE, "c1", "/tmp"));
            scheduler.onFrame(new ShellFrame(ShellFrameType.CMD_END, NONCE, "c1", "0"));
            scheduler.onFrame(new ShellFrame(ShellFrameType.PROMPT, NONCE, "c1", ""));

            PtyCommandScheduler.CommandResult result = future.get(1, TimeUnit.SECONDS);
            assertThat(result.workingDirectory()).isEqualTo("/tmp");
        }
    }

    // ==================================================================
    // 超时与采集（task 5.4）
    // ==================================================================

    @Nested
    @DisplayName("超时与采集")
    class TimeoutAndCollection {

        @Test
        @DisplayName("超时后发送 Ctrl-C 中断")
        void timeoutSendsCtrlC() throws Exception {
            PtyCommandScheduler scheduler = createScheduler(200, 150);
            scheduler.onIntegrationSuccess();

            CompletableFuture<PtyCommandScheduler.CommandResult> future =
                    scheduler.submitCommand("sleep 999");

            // 等待超时触发
            Thread.sleep(600);

            // Ctrl-C (0x03) 应被发送
            assertThat(terminal.sentData).contains("\u0003");
        }

        @Test
        @DisplayName("采集超过 64 KiB 上限后丢弃超额内容但继续排空")
        void collectionOver64KiBDiscardsExcess() throws Exception {
            PtyCommandScheduler scheduler = createScheduler();
            scheduler.onIntegrationSuccess();

            CompletableFuture<PtyCommandScheduler.CommandResult> future =
                    scheduler.submitCommand("yes");

            // 采集大量数据（超过 64 KiB = 65536 字节）
            StringBuilder largeOutput = new StringBuilder();
            for (int i = 0; i < 7000; i++) {
                largeOutput.append("0123456789"); // 每轮 10 字节，共 70000 字节
            }
            scheduler.collectOutput(largeOutput.toString());

            // 完成命令
            scheduler.onFrame(new ShellFrame(ShellFrameType.CMD_END, NONCE, "c1", "0"));
            scheduler.onFrame(new ShellFrame(ShellFrameType.PROMPT, NONCE, "c1", ""));

            PtyCommandScheduler.CommandResult result = future.get(2, TimeUnit.SECONDS);
            // 采集应被截断到 64 KiB
            assertThat(result.stdout().length()).isLessThanOrEqualTo(65536);
            assertThat(result.truncated()).isTrue();
        }

        @Test
        @DisplayName("未超限时结果不包含超时标记")
        void normalCompletionNoTimeout() throws Exception {
            PtyCommandScheduler scheduler = createScheduler();
            scheduler.onIntegrationSuccess();

            CompletableFuture<PtyCommandScheduler.CommandResult> future =
                    scheduler.submitCommand("echo ok");

            scheduler.collectOutput("ok\n");
            scheduler.onFrame(new ShellFrame(ShellFrameType.CMD_END, NONCE, "c1", "0"));
            scheduler.onFrame(new ShellFrame(ShellFrameType.PROMPT, NONCE, "c1", ""));

            PtyCommandScheduler.CommandResult result = future.get(2, TimeUnit.SECONDS);
            assertThat(result.timedOut()).isFalse();
            assertThat(result.stdout()).isEqualTo("ok\n");
        }
    }

    // ==================================================================
    // 中断后 unknown 转换（task 5.4）
    // ==================================================================

    @Nested
    @DisplayName("中断后 unknown 转换")
    class InterruptToUnknown {

        @Test
        @DisplayName("中断后 3 秒无完成证据转为 unknown")
        void interruptThenNoCompletionBecomesUnknown() throws Exception {
            // 使用短超时以加速测试
            PtyCommandScheduler scheduler = createScheduler(100, 150);
            scheduler.onIntegrationSuccess();

            scheduler.submitCommand("sleep 999");

            // 等待超时 + 观察窗口
            Thread.sleep(600);

            assertThat(scheduler.state()).isEqualTo(PtyCommandScheduler.State.UNKNOWN);
        }

        @Test
        @DisplayName("中断后收到完成帧则正常完成而非 unknown")
        void interruptThenCompletionCompletesNormally() throws Exception {
            PtyCommandScheduler scheduler = createScheduler(100, 500);
            scheduler.onIntegrationSuccess();

            CompletableFuture<PtyCommandScheduler.CommandResult> future =
                    scheduler.submitCommand("slow_cmd");

            // 等待超时触发（发送 Ctrl-C）
            Thread.sleep(250);

            // 在观察窗口内发送完成帧
            scheduler.onFrame(new ShellFrame(ShellFrameType.CMD_END, NONCE, "c1", "130"));
            scheduler.onFrame(new ShellFrame(ShellFrameType.PROMPT, NONCE, "c1", ""));

            // 应正常完成而非 unknown
            PtyCommandScheduler.CommandResult result = future.get(1, TimeUnit.SECONDS);
            assertThat(result.exitCode()).isEqualTo(130);
            assertThat(scheduler.state()).isEqualTo(PtyCommandScheduler.State.MANUAL_IDLE);
        }
    }

    // ==================================================================
    // 人工输入干预
    // ==================================================================

    @Nested
    @DisplayName("人工输入干预")
    class ManualIntervention {

        @Test
        @DisplayName("onManualBusy 阻止 Agent 提交")
        void manualBusyBlocksAgentSubmit() {
            PtyCommandScheduler scheduler = createScheduler();
            scheduler.onIntegrationSuccess();

            scheduler.onManualBusy();

            CompletableFuture<PtyCommandScheduler.CommandResult> future =
                    scheduler.submitCommand("echo hello");
            assertThat(future).isCompletedExceptionally();
            assertThat(scheduler.state()).isEqualTo(PtyCommandScheduler.State.MANUAL_BUSY);
        }

        @Test
        @DisplayName("onManualIdle 恢复 Agent 提交能力")
        void manualIdleRestoresAgentSubmit() {
            PtyCommandScheduler scheduler = createScheduler();
            scheduler.onIntegrationSuccess();

            scheduler.onManualBusy();
            scheduler.onManualIdle();

            CompletableFuture<PtyCommandScheduler.CommandResult> future =
                    scheduler.submitCommand("echo hello");
            // 应成功提交
            assertThat(future.isDone()).isFalse(); // 未完成但也没被拒绝
            assertThat(scheduler.state()).isEqualTo(PtyCommandScheduler.State.AGENT_OWNED);
        }

        @Test
        @DisplayName("人工活动后的空 PROMPT 帧自动恢复 idle（接线场景：无此路径则永久 busy）")
        void promptFrameAloneRecoversIdleAfterManualBusy() {
            PtyCommandScheduler scheduler = createScheduler();
            scheduler.onIntegrationSuccess();
            // 用户在人工终端里敲了一行（WS input 钩子标 busy）
            scheduler.onManualBusy();

            // 人工命令执行完毕，bash 钩子回 PROMPT 帧——此时没有 Agent 命令在飞
            // （currentCommand 为 null），旧实现直接 return，状态永卡在 busy，
            // 后续获准命令全被拒；接线后 PROMPT 即"空提示符"的唯一证据
            scheduler.onFrame(new ShellFrame(ShellFrameType.PROMPT, NONCE, "manual", ""));

            assertThat(scheduler.state()).isEqualTo(PtyCommandScheduler.State.MANUAL_IDLE);
            // 恢复后 Agent 可重新领取输入权
            CompletableFuture<PtyCommandScheduler.CommandResult> future =
                    scheduler.submitCommand("pwd");
            assertThat(future).isNotCompletedExceptionally();
        }
    }

    // ==================================================================
    // Stubs
    // ==================================================================

    /**
     * 捕获所有 send() 调用的终端会话桩。
     */
    static class CapturingTerminalSession extends SshTerminalSession {
        final List<String> sentData = new ArrayList<>();

        CapturingTerminalSession() {
            super(UUID.randomUUID().toString(), null, null, null,
                    new SessionRuntimeTest.StubOutputListener(), reason -> { });
        }

        @Override
        public void send(String data) {
            if (data != null && !data.isEmpty()) {
                sentData.add(data);
            }
        }

        @Override
        public void close(SshCloseReason reason) { }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public void resize(int columns, int rows) { }
    }
}
