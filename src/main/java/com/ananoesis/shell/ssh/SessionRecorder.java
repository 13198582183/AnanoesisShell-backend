package com.ananoesis.shell.ssh;

import java.util.UUID;

/**
 * 会话生命周期审计（task 6.6：{@code sessions} 表记录 start/end + close_reason）。
 *
 * <p>WHY 抽成接口而不是让 SSH 服务直接用 Mapper：
 * SSH 服务的测试要对内嵌 SSH 服务器跑真实的连接/中断/超时，
 * 那些测试不需要也不应该背上 SQLite 与 Spring 上下文。
 * 把落库隔离到接口后面，传输层测试就能用手写内存替身，
 * 而落库本身另由 {@code DatabaseSessionRecorderTest} 单独验证。</p>
 */
public interface SessionRecorder {

    /**
     * 记录一次会话开始（{@code status=connecting}）。
     *
     * @return 会话主键；它同时是下发给前端的 {@code session_id}
     */
    String recordStart(UUID hostId, SessionKind kind);

    /** 连接与认证均成功，会话转为可用（{@code status=open}）。 */
    void recordOpen(String sessionId);

    /**
     * 记录会话结束（{@code status=closed} 或 {@code error}），写入 {@code ended_at} 与
     * {@code close_reason}。必须幂等：主动断开、远端关闭与空闲回收可能并发触发。
     *
     * @param detail 仅用于排障的原始信息，写入 {@code error_message}；MUST NOT 含明文凭据
     */
    void recordEnd(String sessionId, SshCloseReason reason, String detail);
}
