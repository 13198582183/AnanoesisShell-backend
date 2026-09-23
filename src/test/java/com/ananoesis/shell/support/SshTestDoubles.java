package com.ananoesis.shell.support;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import com.ananoesis.shell.ssh.SessionKind;
import com.ananoesis.shell.ssh.SessionRecorder;
import com.ananoesis.shell.ssh.SshCloseReason;
import com.ananoesis.shell.ssh.SshTarget;
import com.ananoesis.shell.ssh.SshTargetResolver;
import com.ananoesis.shell.ssh.TerminalOutputListener;

/**
 * tasks 6.x 测试用的手写替身集合。
 *
 * <p>WHY 手写而不用 Mockito：本项目沿用 Wave 1 的测试风格——替身的价值在于
 * "让被测代码只依赖它真正需要的东西"，而 mock 框架会把"验证交互"悄悄变成测试的主体。
 * 下面三个替身都是可读的状态容器：断言直接读它们的公开集合，失败时一眼看得出偏差。</p>
 *
 * <p>WHY 三个替身放一个文件：它们只在 SSH 测试里出现、彼此相关且都很小，
 * 拆成三个文件只会增加跳转成本。</p>
 */
public final class SshTestDoubles {

    private SshTestDoubles() {
    }

    /**
     * 固定目标解析器：无论传入哪个 hostId，都返回构造时给定的目标。
     *
     * <p>WHY 还要记录 {@link #requestedHostIds()}：6.x 的一个真实风险是
     * "解析出来的主机与用户选的主机不是同一台"。只断言连接成功无法发现它，
     * 必须让替身报告"被问过哪些 id"。</p>
     */
    public static final class FixedTargetResolver implements SshTargetResolver {

        private final SshTarget target;
        private final List<UUID> requestedHostIds = new CopyOnWriteArrayList<>();

        public FixedTargetResolver(SshTarget target) {
            this.target = target;
        }

        @Override
        public <T> T withTarget(UUID hostId, Function<SshTarget, T> action) {
            requestedHostIds.add(hostId);
            return action.apply(target);
        }

        public List<UUID> requestedHostIds() {
            return List.copyOf(requestedHostIds);
        }
    }

    /** 内存会话记录器：把 {@link SessionRecorder} 的调用原样存下来供断言。 */
    public static final class InMemorySessionRecorder implements SessionRecorder {

        /** 一条会话记录的完整轨迹。 */
        public record Entry(String sessionId, UUID hostId, SessionKind kind,
                            String status, SshCloseReason closeReason, String detail) {
        }

        private final List<Entry> entries = new CopyOnWriteArrayList<>();
        private int sequence;

        @Override
        public String recordStart(UUID hostId, SessionKind kind) {
            String sessionId = UUID.nameUUIDFromBytes(("session-" + sequence++).getBytes()).toString();
            entries.add(new Entry(sessionId, hostId, kind, "connecting", null, null));
            return sessionId;
        }

        @Override
        public void recordOpen(String sessionId) {
            entries.add(new Entry(sessionId, null, null, "open", null, null));
        }

        @Override
        public void recordEnd(String sessionId, SshCloseReason reason, String detail) {
            entries.add(new Entry(sessionId, null, null, "closed", reason, detail));
        }

        public List<Entry> entries() {
            return List.copyOf(entries);
        }

        /** @return 最后一条 end 记录；不存在时为 null */
        public Entry lastEnd() {
            for (int i = entries.size() - 1; i >= 0; i--) {
                if ("closed".equals(entries.get(i).status())) {
                    return entries.get(i);
                }
            }
            return null;
        }
    }

    /**
     * 记录型终端监听器：累积 stdout/stderr 与关闭事件。
     *
     * <p>WHY 用 {@code CopyOnWriteArrayList} 存分片而不是一次性拼接字符串：
     * "流式"本身就是被测行为——若把分片合并成一个 String，
     * 就无法断言输出确实是**分多次**到达的（一次性返回全部输出也能通过）。</p>
     */
    public static final class RecordingTerminalListener implements TerminalOutputListener {

        private final List<String> stdoutChunks = new CopyOnWriteArrayList<>();
        private final List<String> stderrChunks = new CopyOnWriteArrayList<>();
        private final List<SshCloseReason> closed = new CopyOnWriteArrayList<>();

        @Override
        public void onStdout(String data) {
            stdoutChunks.add(data);
        }

        @Override
        public void onStderr(String data) {
            stderrChunks.add(data);
        }

        @Override
        public void onClosed(SshCloseReason reason) {
            closed.add(reason);
        }

        public String stdout() {
            return String.join("", stdoutChunks);
        }

        public String stderr() {
            return String.join("", stderrChunks);
        }

        /** stdout 分片数：用于断言输出是流式而非一次性到达。 */
        public int stdoutChunkCount() {
            return stdoutChunks.size();
        }

        public List<SshCloseReason> closedReasons() {
            return List.copyOf(closed);
        }

        public boolean isClosed() {
            return !closed.isEmpty();
        }
    }
}
