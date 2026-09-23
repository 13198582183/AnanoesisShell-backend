package com.ananoesis.shell.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * AI 会话（表 {@code ai_conversations}）。
 *
 * <p>对应 ai-agent spec「多轮上下文延续」：一个 conversation 承载一段连续的排障对话，
 * 其下按 seq 挂多条 {@link AiMessage}。</p>
 *
 * <p>WHY {@code hostId} 可空且外键为 {@code ON DELETE SET NULL}：
 * 删除一台服务器的配置不应连带销毁历史对话——对话是审计资产，其价值独立于主机是否还在册。
 * 置空而非级联，正是为了保留"当时针对哪台机器问过什么"的可追溯性下限。</p>
 *
 * <p>WHY {@code sessionId} 可空且外键为 {@code ON DELETE SET NULL}（V2 新增）：
 * 删除会话不应销毁对话审计记录，只解除关联。</p>
 */
@TableName("ai_conversations")
public class AiConversation {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 关联主机，可空；主机被删除时置 null。 */
    private String hostId;

    /** 关联会话，可空（V2 新增）；会话被删除时置 null。 */
    private String sessionId;

    /** 会话标题，通常取首条用户提问的摘要。 */
    private String title;

    /** 状态：{@code active}（进行中）/ {@code archived}（已归档）。 */
    private String status;

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

    public String getHostId() {
        return hostId;
    }

    public void setHostId(String hostId) {
        this.hostId = hostId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
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
        return "AiConversation{id=" + id + ", hostId=" + hostId + ", sessionId=" + sessionId
                + ", title=" + title + ", status=" + status + ", createdAt=" + createdAt + "}";
    }
}
