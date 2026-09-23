package com.ananoesis.shell.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * SSH 连接会话生命周期记录（表 {@code sessions}）。
 *
 * <p>WHY 类名是 {@code SshSession} 而表名是 {@code sessions}：
 * {@code Session} 与 WebSocket / SSH 库的同名类型极易混淆，加前缀让审计代码一眼可辨。</p>
 *
 * <p>对应 ssh-connection spec「连接会话生命周期」：记录连接建立、状态流转与终止原因，
 * 使"连接失败：主机不可达 / 认证失败 / 连接超时"等提示可回溯到具体会话。</p>
 *
 * <p>{@link #sessionType} 区分 design.md D4 的两条独立通道：交互式 PTY（人用的终端）
 * 与 exec（AI 跑命令的通道）——审计必须能分辨命令是人敲的还是 AI 发起的。</p>
 */
@TableName("sessions")
public class SshSession {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 所属主机，外键 {@code ON DELETE CASCADE}：删主机配置即清理其会话记录。 */
    private String hostId;

    /** 通道类型：{@code interactive_pty} / {@code exec}。 */
    private String sessionType;

    /** 状态：{@code connecting} / {@code open} / {@code closed} / {@code error}。 */
    private String status;

    /** 连接建立时刻（DDL NOT NULL）。 */
    private LocalDateTime startedAt;

    /** 会话结束时刻，未结束时为 null。 */
    private LocalDateTime endedAt;

    /**
     * 终止原因：{@code user_disconnect} / {@code remote_closed} / {@code timeout}
     * / {@code auth_failed} / {@code unreachable} / {@code error}。
     * WHY 枚举化而非自由文本：spec 要求对"主动断开 / 超时 / 远端关闭"给出不同提示，
     * 自由文本无法可靠分流。
     */
    private String closeReason;

    /** 原始错误信息，仅供排障；MUST NOT 含凭据。 */
    private String errorMessage;

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

    public String getSessionType() {
        return sessionType;
    }

    public void setSessionType(String sessionType) {
        this.sessionType = sessionType;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
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

    public String getCloseReason() {
        return closeReason;
    }

    public void setCloseReason(String closeReason) {
        this.closeReason = closeReason;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
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
        return "SshSession{id=" + id + ", hostId=" + hostId + ", sessionType=" + sessionType
                + ", status=" + status + ", startedAt=" + startedAt + ", endedAt=" + endedAt
                + ", closeReason=" + closeReason + "}";
    }
}
