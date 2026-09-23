package com.ananoesis.shell.service;

/**
 * 请求的 AI 会话不存在（ai-agent spec「多轮上下文延续」）。
 *
 * <p>WHY 单独建类型而不复用 {@link NotFoundException}：与 {@link HostNotFoundException} 同理——
 * 智能体回合在跑之前要确认会话还在（用户可能刚在界面上删掉它），
 * 此时需要把"会话没了"翻译成 {@code ai_stream(type=error, error_code=not_found)}，
 * 而"主机没了"要翻译成另一句面向用户的文案。精确 catch 比看消息字符串可靠。</p>
 */
public class ConversationNotFoundException extends NotFoundException {

    public ConversationNotFoundException(String message) {
        super(message);
    }

    /** @param conversationId 未命中的主键，仅用于消息拼装（非敏感坐标） */
    public static ConversationNotFoundException forId(String conversationId) {
        return new ConversationNotFoundException("AI 会话不存在: id=" + conversationId);
    }
}
