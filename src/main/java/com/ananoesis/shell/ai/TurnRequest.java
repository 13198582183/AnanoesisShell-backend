package com.ananoesis.shell.ai;

import java.util.Objects;
import java.util.UUID;

import org.springframework.lang.Nullable;

/**
 * 一次待执行的智能体回合（tasks 9.4）。
 *
 * <p>由 {@code AiWebSocketHandler} 从上行 {@code ai_stream(type=user_message)} 帧翻译而来，
 * 交给 {@link AiAgentService} 在工作线程上跑。</p>
 *
 * <h2>WHY {@code hostId} 可空</h2>
 * <p>契约 {@code Conversation.host_id} 是 {@code nullable}（TRACEABILITY Q7），
 * 上行帧的 {@code host_id} 也是可选的。一个没绑定服务器的会话是合法的：
 * 用户可以纯粹问"nginx 502 一般怎么排查"。此时智能体照常回答，只是<b>没有工具可用</b>——
 * 四个工具全都要落到某台机器上执行，没有目标机器就没有可执行的工具。
 * 与其报错打断对话，不如把工具集清空、并在系统提示里说明这一点。</p>
 *
 * <h2>WHY 在这里就要求 {@code userText} 非空</h2>
 * <p>空提问会让模型收到一条内容为空的 user 消息，多数端点回一句"请提供您的问题"，
 * 用户看到的是一轮毫无意义的往返；更糟的是它已经占了一个工作线程、写了一行
 * {@code ai_messages}。上行校验（handler 侧）与本处校验是同一件事的两道闸，
 * 后者保证即使有人绕过 WebSocket 直接调服务也不会写出空消息。</p>
 *
 * <h2>WHY 携带 {@code sessionId}</h2>
 * <p>design.md D3：「所有 Agent 远端工具通过所属 runtime 的同一持久 Shell 执行」。
 * 前端在 {@code user_message} 帧里带上当前连接实例的 session_id，智能体据此把
 * 获准命令与只读工具路由到该会话的持久 PTY（cd/export 状态得以继承）；
 * 缺省时（旧客户端或未建连接的纯问答）回落 exec 通道。</p>
 *
 * @param conversationId 会话 id（契约 required）
 * @param hostId         本回合的目标服务器；null 表示"未绑定，无工具可用"
 * @param userText       用户提问原文
 * @param sessionId      发起本回合的连接实例 id；null 表示无持久 Shell 可复用
 */
public record TurnRequest(UUID conversationId, @Nullable UUID hostId, String userText,
                          @Nullable UUID sessionId) {

    /**
     * 三参兼容构造器：既有调用方（测试与未升级路径）不带连接实例 id，
     * 语义等价于"无持久 Shell，走 exec 通道"。
     */
    public TurnRequest(UUID conversationId, @Nullable UUID hostId, String userText) {
        this(conversationId, hostId, userText, null);
    }

    public TurnRequest {
        Objects.requireNonNull(conversationId, "conversationId 不得为 null");
        Objects.requireNonNull(userText, "userText 不得为 null");
        if (userText.isBlank()) {
            throw new IllegalArgumentException("userText 不得为空白");
        }
    }

    /**
     * WHY 覆写：record 默认 toString 会打出提问原文。用户可能在提问里粘贴
     * 一段含口令的配置或命令，而 toString 是日志框架最容易顺手调用的方法。
     */
    @Override
    public String toString() {
        return "TurnRequest{conversationId=" + conversationId + ", hostId=" + hostId
                + ", textLength=" + userText.length() + "}";
    }
}
