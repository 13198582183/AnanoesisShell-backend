package com.ananoesis.shell.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 命令执行账本（表 {@code command_executions}）。
 *
 * <p>对应 design.md D10「命令目标快照」：记录每条命令的执行状态、来源、输出等，
 * 用于审计和状态追踪。</p>
 *
 * <p>WHY {@code source} 区分 {@code agent_tool} / {@code manual}：
 * 审计必须能分辨 AI 发起与人手动执行的命令。</p>
 *
 * <p>WHY 唯一索引 {@code (run_id, call_id) WHERE source = 'agent_tool'}：
 * agent 工具调用必须幂等可追溯，防止重复执行。</p>
 */
@TableName("command_executions")
public class CommandExecution {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /**
     * 命令来源：{@code agent_tool}（AI 工具调用）/ {@code manual}（人工执行）。
     * WHY 枚举化而非自由文本：审计必须可靠区分来源。
     */
    private String source;

    /** 关联的运行记录，可空（外键 {@code ON DELETE SET NULL}）。 */
    private String runId;

    /** 工具调用 id，用于配对 AI 消息中的 tool_call。 */
    private String callId;

    /** 所属会话，外键 {@code ON DELETE CASCADE}：删会话即清理其命令记录。 */
    private String sessionId;

    /** 关联的对话，可空（外键 {@code ON DELETE SET NULL}）。 */
    private String conversationId;

    /** 执行的命令原文。MUST NOT 含明文凭据。 */
    private String command;

    /**
     * 认领状态：{@code claimed} / {@code sent} / {@code completed} / {@code unknown}。
     * WHY {@code unknown} 独立：远端状态不确定时不应阻止记录落库。
     */
    private String claimStatus;

    /** 命令执行的工作目录。 */
    private String cwd;

    /** 命令退出码。 */
    private Integer exitCode;

    /** 输出是否被截断：0=否，1=是。 */
    private Integer outputTruncated;

    /** 标准输出。 */
    private String stdout;

    /** 标准错误输出。 */
    private String stderr;

    /** 命令被认领时刻。 */
    private LocalDateTime claimedAt;

    /** 命令发送时刻。 */
    private LocalDateTime sentAt;

    /** 命令完成时刻。 */
    private LocalDateTime completedAt;

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

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public String getCallId() {
        return callId;
    }

    public void setCallId(String callId) {
        this.callId = callId;
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

    public String getCommand() {
        return command;
    }

    public void setCommand(String command) {
        this.command = command;
    }

    public String getClaimStatus() {
        return claimStatus;
    }

    public void setClaimStatus(String claimStatus) {
        this.claimStatus = claimStatus;
    }

    public String getCwd() {
        return cwd;
    }

    public void setCwd(String cwd) {
        this.cwd = cwd;
    }

    public Integer getExitCode() {
        return exitCode;
    }

    public void setExitCode(Integer exitCode) {
        this.exitCode = exitCode;
    }

    public Integer getOutputTruncated() {
        return outputTruncated;
    }

    public void setOutputTruncated(Integer outputTruncated) {
        this.outputTruncated = outputTruncated;
    }

    public String getStdout() {
        return stdout;
    }

    public void setStdout(String stdout) {
        this.stdout = stdout;
    }

    public String getStderr() {
        return stderr;
    }

    public void setStderr(String stderr) {
        this.stderr = stderr;
    }

    public LocalDateTime getClaimedAt() {
        return claimedAt;
    }

    public void setClaimedAt(LocalDateTime claimedAt) {
        this.claimedAt = claimedAt;
    }

    public LocalDateTime getSentAt() {
        return sentAt;
    }

    public void setSentAt(LocalDateTime sentAt) {
        this.sentAt = sentAt;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(LocalDateTime completedAt) {
        this.completedAt = completedAt;
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

    /**
     * WHY 不打印 command / stdout / stderr：命令原文与输出可能含敏感信息，
     * 只输出定位所需的标识与状态字段。
     */
    @Override
    public String toString() {
        return "CommandExecution{id=" + id + ", source=" + source + ", sessionId=" + sessionId
                + ", claimStatus=" + claimStatus + ", exitCode=" + exitCode + "}";
    }
}
