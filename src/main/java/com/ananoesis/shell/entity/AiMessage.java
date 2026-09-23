package com.ananoesis.shell.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * AI 会话消息与工具调用明细（表 {@code ai_messages}）。
 *
 * <p>WHY {@code reasoningContent} 单独成列（model-provider spec「思考与非思考双模式」）：
 * 思考过程需在界面上与最终回答**分区展示**，混进 {@code content} 后将无法区分，
 * 也无法支持"只看结论"的折叠视图。</p>
 *
 * <p>WHY {@code toolName} / {@code toolArguments} / {@code toolResult} 单独成列
 * （ai-agent spec「操作透明性」）：每一次工具调用的名称、参数与结果（或"用户已拒绝"）
 * 都必须可追溯、可检索；塞进 JSON 大字段会让审计查询退化为全表扫描。</p>
 *
 * <p>WHY {@code source} 区分 ai / shell_event（V2 新增）：
 * shell 事件（如连接状态变化）需与 AI 消息在审计上可区分。</p>
 *
 * <p>本表只有 {@code createdAt} 而无 {@code updatedAt}：消息是**不可变**的追加日志，
 * 允许更新会破坏"AI 当时究竟说了什么"的审计价值。</p>
 */
@TableName("ai_messages")
public class AiMessage {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 所属会话，外键 {@code ON DELETE CASCADE}。 */
    private String conversationId;

    /**
     * 会话内单调递增序号，{@code (conversation_id, seq)} 唯一。
     * WHY 由应用侧显式赋值而非依赖插入顺序：多轮上下文重建必须严格按 seq 排序，
     * 而"靠 rowid 排序"在数据导入/迁移后会失真。
     */
    private Integer seq;

    /** 角色：{@code system} / {@code user} / {@code assistant} / {@code tool}。 */
    private String role;

    /** 消息正文。 */
    private String content;

    /** 模型思考过程（reasoning_content），可为 null（非思考模式）。 */
    private String reasoningContent;

    /** 本条消息发起的工具调用列表（JSON 文本）。 */
    private String toolCalls;

    /** 当 role=tool 时，对应的工具调用 id，用于把结果配对回调用。 */
    private String toolCallId;

    /** 当 role=tool 时，被调用的工具名，如 {@code run_command}。 */
    private String toolName;

    /** 当 role=tool 时，工具入参（JSON 文本）。MUST NOT 含明文凭据。 */
    private String toolArguments;

    /** 工具执行结果；被用户拒绝时记录拒绝事实。 */
    private String toolResult;

    /** 该工具调用是否被用户拒绝：0=否，1=是。 */
    private Integer toolRejected;

    /**
     * 消息来源（V2 新增）：{@code ai}（AI 生成）/ {@code shell_event}（Shell 事件）。
     * WHY 默认 'ai'：保持与 V1 数据的向后兼容。
     */
    private String source;

    /** 关联的命令执行 id（V2 新增），可空。 */
    private String commandId;

    /** 关联的运行记录 id（V2 新增），可空。 */
    private String runId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public Integer getSeq() {
        return seq;
    }

    public void setSeq(Integer seq) {
        this.seq = seq;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getReasoningContent() {
        return reasoningContent;
    }

    public void setReasoningContent(String reasoningContent) {
        this.reasoningContent = reasoningContent;
    }

    public String getToolCalls() {
        return toolCalls;
    }

    public void setToolCalls(String toolCalls) {
        this.toolCalls = toolCalls;
    }

    public String getToolCallId() {
        return toolCallId;
    }

    public void setToolCallId(String toolCallId) {
        this.toolCallId = toolCallId;
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public String getToolArguments() {
        return toolArguments;
    }

    public void setToolArguments(String toolArguments) {
        this.toolArguments = toolArguments;
    }

    public String getToolResult() {
        return toolResult;
    }

    public void setToolResult(String toolResult) {
        this.toolResult = toolResult;
    }

    public Integer getToolRejected() {
        return toolRejected;
    }

    public void setToolRejected(Integer toolRejected) {
        this.toolRejected = toolRejected;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getCommandId() {
        return commandId;
    }

    public void setCommandId(String commandId) {
        this.commandId = commandId;
    }

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    /**
     * WHY 不打印 content / toolArguments：消息正文与工具入参可能含用户粘贴的敏感片段，
     * 只输出定位所需的标识与序号。
     */
    @Override
    public String toString() {
        return "AiMessage{id=" + id + ", conversationId=" + conversationId + ", seq=" + seq
                + ", role=" + role + ", source=" + source + ", toolName=" + toolName
                + ", toolRejected=" + toolRejected + "}";
    }
}
