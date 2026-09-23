package com.ananoesis.shell.support;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketExtension;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * WebSocket 会话的手写替身：记录发出的每一帧，可注入发送失败。
 *
 * <p>WHY 不用 Spring 自带的 mock：{@code spring-websocket} 没有提供
 * {@code WebSocketSession} 的测试实现（它的 {@code StandardWebSocketSession}
 * 需要一个真实的 JSR-356 会话）。而 {@code TerminalWebSocketHandlerTest}
 * 要断言的恰恰是"发出去的帧长什么样"——一个会累积载荷的替身比 mock 的
 * {@code verify(...)} 更直白，失败时能直接把整串帧打印出来。</p>
 *
 * <p>WHY {@code sent} 用 {@link CopyOnWriteArrayList}：帧由 stdout/stderr 两条
 * 读泵线程并发写入，断言则在测试主线程读取。</p>
 *
 * <p>WHY 提供 {@link #failOnSend(boolean)}：真实场景里"对端已经走了但我们还在写"
 * 必然发生（用户直接关掉标签页）。处理器必须把这类 IOException 咽下去，
 * 否则异常会沿读泵线程上抛，导致 SSH 会话的关闭流程被中断、资源泄漏。
 * 不注入这个失败，那条 catch 分支就永远测不到。</p>
 */
public final class FakeWebSocketSession implements WebSocketSession {

    private final String id;
    private final List<TextMessage> sent = new CopyOnWriteArrayList<>();
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();
    private final AtomicBoolean open = new AtomicBoolean(true);
    private final AtomicBoolean failing = new AtomicBoolean(false);
    private final AtomicReference<CloseStatus> closeStatus = new AtomicReference<>();

    public FakeWebSocketSession() {
        this(UUID.randomUUID().toString());
    }

    public FakeWebSocketSession(String id) {
        this.id = id;
    }

    // ==================================================================
    // 断言入口
    // ==================================================================

    /** 已发出的全部文本帧载荷，按发送顺序。 */
    public List<String> sentPayloads() {
        return sent.stream().map(TextMessage::getPayload).toList();
    }

    public int sentCount() {
        return sent.size();
    }

    /** 让后续 {@code sendMessage} 抛 {@link IOException}，模拟对端已消失。 */
    public void failOnSend(boolean value) {
        failing.set(value);
    }

    /** @return 关闭时使用的状态；未关闭为 null */
    public CloseStatus closeStatus() {
        return closeStatus.get();
    }

    // ==================================================================
    // WebSocketSession
    // ==================================================================

    @Override
    public void sendMessage(WebSocketMessage<?> message) throws IOException {
        if (!open.get()) {
            throw new IOException("WebSocket 会话已关闭");
        }
        if (failing.get()) {
            throw new IOException("模拟发送失败（对端已消失）");
        }
        if (!(message instanceof TextMessage text)) {
            throw new IllegalArgumentException("终端通道只应发出文本帧，实际为 " + message.getClass().getName());
        }
        sent.add(text);
    }

    @Override
    public boolean isOpen() {
        return open.get();
    }

    @Override
    public void close() throws IOException {
        close(CloseStatus.NORMAL);
    }

    @Override
    public void close(CloseStatus status) throws IOException {
        open.set(false);
        closeStatus.compareAndSet(null, status);
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public URI getUri() {
        return URI.create("ws://localhost:18080/ws/terminal");
    }

    @Override
    public HttpHeaders getHandshakeHeaders() {
        return new HttpHeaders();
    }

    @Override
    public Map<String, Object> getAttributes() {
        return attributes;
    }

    @Override
    public Principal getPrincipal() {
        return null;
    }

    @Override
    public InetSocketAddress getLocalAddress() {
        return new InetSocketAddress("127.0.0.1", 18080);
    }

    @Override
    public InetSocketAddress getRemoteAddress() {
        return new InetSocketAddress("127.0.0.1", 54321);
    }

    @Override
    public String getAcceptedProtocol() {
        return null;
    }

    @Override
    public void setTextMessageSizeLimit(int messageSizeLimit) {
        // 替身不限制帧大小
    }

    @Override
    public int getTextMessageSizeLimit() {
        return 0;
    }

    @Override
    public void setBinaryMessageSizeLimit(int messageSizeLimit) {
        // 终端通道不使用二进制帧
    }

    @Override
    public int getBinaryMessageSizeLimit() {
        return 0;
    }

    @Override
    public List<WebSocketExtension> getExtensions() {
        return List.of();
    }
}
