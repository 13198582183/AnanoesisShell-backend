package com.ananoesis.shell.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * AI 运行记录（表 {@code ai_runs}）。
 *
 * <p>对应 design.md D10「运行账本」：每次 AI 执行（一轮从开始到结束）对应一条记录，
 * 用于追踪运行状态、模型配置快照、上下文恢复次数等。</p>
 *
 * <p>WHY {@code modelConfigSnapshot} 为 TEXT 而非 JSON 列：
 * SQLite 无原生 JSON 类型，且 V2 迁移不使用 JSON 扩展；以 TEXT 存储 JSON 快照
 * 既保留完整配置信息，又不引入额外依赖。</p>
 *
 * <p>WHY 唯一索引 {@code (session_id) WHERE status = 'running'}：
 * 同一会话同时只能有一条 running 状态的运行记录，防止并发运行导致状态混乱。</p>
 */
@TableName("ai_runs")
public class AiRun {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 所属会话，外键 {@code ON DELETE CASCADE}：删会话即清理其运行记录。 */
    private String sessionId;

    /** 关联的对话，可空（外键 {@code ON DELETE SET NULL}）。 */
    private String conversationId;

    /**
     * 运行状态：{@code running} / {@code completed} / {@code failed} / {@code stopped} / {@code unknown}。
     * WHY {@code unknown} 独立：远端状态不确定时不应阻止记录落库。
     */
    private String status;

    /** 模型配置快照（JSON 文本），记录运行时的模型参数。 */
    private String modelConfigSnapshot;

    /** 上下文恢复次数：因上下文丢失而重试的次数。 */
    private Integer contextRecoveryCount;

    /** 取消代数：用于并发取消控制。 */
    private Integer cancellationGeneration;

    /** 运行开始时刻。 */
    private LocalDateTime startedAt;

    /** 运行结束时刻，未结束时为 null。 */
    private LocalDateTime endedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getModelConfigSnapshot() {
        return modelConfigSnapshot;
    }

    public void setModelConfigSnapshot(String modelConfigSnapshot) {
        this.modelConfigSnapshot = modelConfigSnapshot;
    }

    public Integer getContextRecoveryCount() {
        return contextRecoveryCount;
    }

    public void setContextRecoveryCount(Integer contextRecoveryCount) {
        this.contextRecoveryCount = contextRecoveryCount;
    }

    public Integer getCancellationGeneration() {
        return cancellationGeneration;
    }

    public void setCancellationGeneration(Integer cancellationGeneration) {
        this.cancellationGeneration = cancellationGeneration;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(LocalDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public LocalDateTime getEndedAt() {
        return endedAt;
    }

    public void setEndedAt(LocalDateTime endedAt) {
        this.endedAt = endedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    @Override
    public String toString() {
        return "AiRun{id=" + id + ", sessionId=" + sessionId + ", status=" + status
                + ", startedAt=" + startedAt + ", endedAt=" + endedAt + "}";
    }
}
