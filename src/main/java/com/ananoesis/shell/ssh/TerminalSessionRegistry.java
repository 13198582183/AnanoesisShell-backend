package com.ananoesis.shell.ssh;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 活跃交互式终端会话的注册表（task 6.6 的资源侧）。
 *
 * <p>WHY 需要一个显式注册表，而不是让 WebSocket 层自己持有 session：
 * WebSocket 连接与 SSH 会话的生命周期<b>并不重合</b>——用户刷新页面时 WS 断了，
 * 但 SSH 会话可能还该活一会儿（重连续用）；反过来，用户直接关掉浏览器标签页时
 * 前端来不及发 {@code action=close}，只有服务端的空闲回收能兜底。
 * 注册表让"有哪些会话还活着"成为服务端自己可回答的问题。</p>
 *
 * <p>WHY 用 {@link ConcurrentHashMap} 而不加锁：会话的注册/注销来自 WS 线程、
 * 读泵线程与定时回收线程三方，加一把全局锁会让"回收一个会话"阻塞"新建一个会话"。
 * 注册表只存引用、不做业务判断，天然适合无锁并发容器。</p>
 */
@Component
public class TerminalSessionRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(TerminalSessionRegistry.class);

    private final Map<String, SshTerminalSession> sessions = new ConcurrentHashMap<>();

    public void register(SshTerminalSession session) {
        Objects.requireNonNull(session, "session 不得为 null");
        SshTerminalSession previous = sessions.put(session.id(), session);
        if (previous != null) {
            // 会话 id 是 UUID，正常情况下不可能重复；真发生了说明有 bug，必须留痕
            LOG.warn("会话 id 冲突，旧会话被覆盖并已关闭: id={}", session.id());
            previous.close(SshCloseReason.ERROR);
        }
    }

    public void unregister(String sessionId) {
        if (sessionId != null) {
            sessions.remove(sessionId);
        }
    }

    public Optional<SshTerminalSession> find(String sessionId) {
        return Optional.ofNullable(sessionId == null ? null : sessions.get(sessionId));
    }

    /** 当前全部活跃会话的快照（顺序不保证）。 */
    public List<SshTerminalSession> all() {
        return List.copyOf(sessions.values());
    }

    public int size() {
        return sessions.size();
    }

    /**
     * 回收空闲超过 {@code idleTimeout} 的会话。
     *
     * <p>WHY 必须有它：用户直接关掉标签页时前端来不及发 {@code action=close}，
     * 若不回收，每条被遗弃的会话都会一直占着一个 socket、两条读线程和一条远端 shell，
     * 直到进程退出。桌面应用长时间运行，这类泄漏会累积成"越用越卡"。</p>
     *
     * <p>WHY 先取快照再遍历：{@code close()} 会回调 {@link #unregister}，
     * 直接在 {@code values()} 视图上迭代并修改，虽然 {@code ConcurrentHashMap} 不会抛
     * 并发修改异常，但迭代视图可能漏掉或重复元素，导致返回的回收数不准。</p>
     *
     * @return 实际回收的会话数
     */
    public int expireIdleSessions(Duration idleTimeout) {
        Objects.requireNonNull(idleTimeout, "idleTimeout 不得为 null");
        Instant cutoff = Instant.now().minus(idleTimeout);
        List<SshTerminalSession> snapshot = all();
        int expired = 0;
        for (SshTerminalSession session : snapshot) {
            // 用 !isAfter 而不是 isBefore：Duration.ZERO 时 cutoff == now，
            // 刚刚建立的会话也应被视为"已到期"，这让本方法可以被直接测试
            if (!session.lastActivityAt().isAfter(cutoff) && session.isOpen()) {
                session.close(SshCloseReason.TIMEOUT);
                expired++;
            }
        }
        if (expired > 0) {
            LOG.info("已回收空闲终端会话: expired={} idleTimeout={} remaining={}",
                    expired, idleTimeout, sessions.size());
        }
        return expired;
    }
}
