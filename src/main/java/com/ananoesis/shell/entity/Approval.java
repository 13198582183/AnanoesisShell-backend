package com.ananoesis.shell.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 人工审批记录与审计日志（表 {@code approvals}）。
 *
 * <p>对应 command-approval spec「审批审计日志」：每条记录至少含时间、目标服务器、
 * 工具名与参数、AI 分析、用户决定、执行结果——本实体字段与该清单一一对应。</p>
 *
 * <p>WHY {@code decision} 与 {@code executionStatus} 分开：
 * "用户批准了"与"命令真的跑成功了"是两件事。批准后仍可能超时、失败或输出被截断，
 * 审计必须能**同时**还原人的决定与机器的执行结果，合成一个字段会丢失其中一半事实。</p>
 *
 * <p>WHY {@code decidedBy} 区分 user / system：审批超时由系统按"取消"兜底
 * （spec「审批超时自动取消」），必须与用户主动点击"取消"在审计上可区分，
 * 否则事后无法判断是人不想要还是人没来得及看。</p>
 *
 * <p>WHY {@code version} / {@code expectedVersion}（V2 新增）：
 * 乐观并发控制，防止并发修改审批时丢失更新。</p>
 *
 * <p>安全约束：{@code toolArguments} / {@code executionResult} 等文本列 MUST NOT 含明文凭据；
 * 命令文本由服务层脱敏后再落库。</p>
 */
@TableName("approvals")
public class Approval {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 发起该工具调用的 AI 会话，可空（外键 ON DELETE SET NULL）。 */
    private String conversationId;

    /** 承载该工具调用的消息 id，可空。 */
    private String messageId;

    /** 命令实际执行的 SSH 会话 id，可空。 */
    private String sessionId;

    /** 目标服务器 id——审计要素「目标服务器」，可空。 */
    private String hostId;

    /** 工具名，如 {@code run_command} / {@code read_file}。审计要素「工具名」。 */
    private String toolName;

    /** 工具入参（JSON 文本）。审计要素「参数」。 */
    private String toolArguments;

    /** AI 给出的风险分析与操作理由。审计要素「AI 分析」。 */
    private String aiAnalysis;

    /**
     * 用户决定：{@code pending} / {@code approved} / {@code rejected} / {@code timeout}。
     * WHY {@code timeout} 独立于 {@code rejected}：超时是"未获得决定"，
     * 而 rejected 是"明确否决"，两者的责任归属完全不同。
     */
    private String decision;

    /** 决定作出方：{@code user} / {@code system}（超时兜底）。 */
    private String decidedBy;

    /** 审批发起时刻（DDL NOT NULL）。审计要素「时间」。 */
    private LocalDateTime requestedAt;

    /** 审批失效时刻；超过即按 timeout 处理并释放挂起资源。 */
    private LocalDateTime expiresAt;

    /** 决定作出时刻。 */
    private LocalDateTime decidedAt;

    /**
     * 执行状态：{@code not_executed} / {@code running} / {@code success} / {@code failed}
     * / {@code timeout} / {@code truncated}。
     * WHY {@code truncated} 独立于 {@code success}：spec 要求输出超限时明确标注"输出已截断"，
     * 用户必须知道看到的不是完整结果。
     */
    private String executionStatus;

    /** 执行结果摘要（含退出码与截断后的输出）。审计要素「执行结果」。 */
    private String executionResult;

    /** 命令退出码。 */
    private Integer exitCode;

    /** 输出是否被截断：0=否，1=是。 */
    private Integer outputTruncated;

    /** 关联的运行记录 id（V2 新增），可空（外键 ON DELETE SET NULL）。 */
    private String runId;

    /** 关联的工具调用 id（V2 新增），可空。 */
    private String callId;

    /**
     * 审批版本号（V2 新增），用于乐观并发控制。
     * WHY 默认 1：与数据库 DDL DEFAULT 保持一致。
     */
    private Integer version;

    /**
     * 期望版本号（V2 新增），用于乐观并发控制。
     * WHY 默认 1：与数据库 DDL DEFAULT 保持一致。
     */
    private Integer expectedVersion;

    /** 审批通过后可能被修改的最终命令（V2 新增）。 */
    private String finalCommand;

    /** 审批目标会话 id（V2 新增），可与执行会话不同，可空。 */
    private String targetSessionId;

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

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getHostId() {
        return hostId;
    }

    public void setHostId(String hostId) {
        this.hostId = hostId;
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

    public String getAiAnalysis() {
        return aiAnalysis;
    }

    public void setAiAnalysis(String aiAnalysis) {
        this.aiAnalysis = aiAnalysis;
    }

    public String getDecision() {
        return decision;
    }

    public void setDecision(String decision) {
        this.decision = decision;
    }

    public String getDecidedBy() {
        return decidedBy;
    }

    public void setDecidedBy(String decidedBy) {
        this.decidedBy = decidedBy;
    }

    public LocalDateTime getRequestedAt() {
        return requestedAt;
    }

    public void setRequestedAt(LocalDateTime requestedAt) {
        this.requestedAt = requestedAt;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }

    public LocalDateTime getDecidedAt() {
        return decidedAt;
    }

    public void setDecidedAt(LocalDateTime decidedAt) {
        this.decidedAt = decidedAt;
    }

    public String getExecutionStatus() {
        return executionStatus;
    }

    public void setExecutionStatus(String executionStatus) {
        this.executionStatus = executionStatus;
    }

    public String getExecutionResult() {
        return executionResult;
    }

    public void setExecutionResult(String executionResult) {
        this.executionResult = executionResult;
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

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public Integer getExpectedVersion() {
        return expectedVersion;
    }

    public void setExpectedVersion(Integer expectedVersion) {
        this.expectedVersion = expectedVersion;
    }

    public String getFinalCommand() {
        return finalCommand;
    }

    public void setFinalCommand(String finalCommand) {
        this.finalCommand = finalCommand;
    }

    public String getTargetSessionId() {
        return targetSessionId;
    }

    public void setTargetSessionId(String targetSessionId) {
        this.targetSessionId = targetSessionId;
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
     * WHY 不打印 toolArguments / aiAnalysis / executionResult：这三列可能含命令原文与
     * 输出片段（用户可能在命令行里带过密码），审计打印只保留可定位的标识与结论字段。
     */
    @Override
    public String toString() {
        return "Approval{id=" + id + ", hostId=" + hostId + ", toolName=" + toolName
                + ", decision=" + decision + ", decidedBy=" + decidedBy
                + ", requestedAt=" + requestedAt + ", decidedAt=" + decidedAt
                + ", executionStatus=" + executionStatus + ", exitCode=" + exitCode
                + ", outputTruncated=" + outputTruncated + ", version=" + version + "}";
    }
}
