package com.ananoesis.shell.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 文件传输记录（表 {@code file_transfers}）。
 *
 * <p>对应 design.md D10「SFTP 传输」：记录每次文件传输的状态、进度、元数据等，
 * 用于审计和状态追踪。</p>
 *
 * <p>WHY {@code direction} 区分 {@code upload} / {@code download}：
 * 传输方向是审计要素，必须明确区分。</p>
 *
 * <p>WHY {@code expectedTarget} 为 TEXT 而非 JSON 列：
 * SQLite 无原生 JSON 类型，以 TEXT 存储 JSON 格式 {@code {size, mtime, mode}}
 * 既保留完整目标信息，又不引入额外依赖。</p>
 */
@TableName("file_transfers")
public class FileTransfer {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 所属会话，外键 {@code ON DELETE CASCADE}：删会话即清理其传输记录。 */
    private String sessionId;

    /**
     * 传输方向：{@code upload}（上传到远端）/ {@code download}（从远端下载）。
     * WHY 枚举化而非自由文本：审计必须可靠区分方向。
     */
    private String direction;

    /** 远端文件路径。 */
    private String remotePath;

    /** 文件名，用于显示。 */
    private String fileName;

    /** 声明的文件大小（字节）。 */
    private Long declaredSize;

    /** 已传输字节数。 */
    private Long transferredBytes;

    /**
     * 传输状态：{@code queued} / {@code ready} / {@code transferring} / {@code publishing}
     * / {@code published} / {@code delivered} / {@code failed} / {@code cancelled}
     * / {@code expired} / {@code unknown}。
     * WHY {@code unknown} 独立：远端状态不确定时不应阻止记录落库。
     */
    private String status;

    /** 是否覆盖已存在文件：0=否，1=是。 */
    private Integer overwrite;

    /** 预期目标信息（JSON 文本：{size, mtime, mode}）。 */
    private String expectedTarget;

    /** 临时文件路径，用于传输过程中的暂存。 */
    private String tempFilePath;

    /** 失败代码，用于分类失败原因。 */
    private String failureCode;

    /** 下载票据哈希，用于验证下载请求。 */
    private String downloadTicketHash;

    /** 下载票据过期时刻。 */
    private LocalDateTime downloadTicketExpiresAt;

    /** 加入队列时刻。 */
    private LocalDateTime queuedAt;

    /** 就绪时刻（文件已准备好开始传输）。 */
    private LocalDateTime readyAt;

    /** 就绪截止时间，超时则过期。 */
    private LocalDateTime readyDeadline;

    /** 传输开始时刻。 */
    private LocalDateTime transferStartedAt;

    /** 传输完成时刻。 */
    private LocalDateTime transferCompletedAt;

    /** 发布时刻（文件已发布到目标位置）。 */
    private LocalDateTime publishedAt;

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

    public String getDirection() {
        return direction;
    }

    public void setDirection(String direction) {
        this.direction = direction;
    }

    public String getRemotePath() {
        return remotePath;
    }

    public void setRemotePath(String remotePath) {
        this.remotePath = remotePath;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public Long getDeclaredSize() {
        return declaredSize;
    }

    public void setDeclaredSize(Long declaredSize) {
        this.declaredSize = declaredSize;
    }

    public Long getTransferredBytes() {
        return transferredBytes;
    }

    public void setTransferredBytes(Long transferredBytes) {
        this.transferredBytes = transferredBytes;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getOverwrite() {
        return overwrite;
    }

    public void setOverwrite(Integer overwrite) {
        this.overwrite = overwrite;
    }

    public String getExpectedTarget() {
        return expectedTarget;
    }

    public void setExpectedTarget(String expectedTarget) {
        this.expectedTarget = expectedTarget;
    }

    public String getTempFilePath() {
        return tempFilePath;
    }

    public void setTempFilePath(String tempFilePath) {
        this.tempFilePath = tempFilePath;
    }

    public String getFailureCode() {
        return failureCode;
    }

    public void setFailureCode(String failureCode) {
        this.failureCode = failureCode;
    }

    public String getDownloadTicketHash() {
        return downloadTicketHash;
    }

    public void setDownloadTicketHash(String downloadTicketHash) {
        this.downloadTicketHash = downloadTicketHash;
    }

    public LocalDateTime getDownloadTicketExpiresAt() {
        return downloadTicketExpiresAt;
    }

    public void setDownloadTicketExpiresAt(LocalDateTime downloadTicketExpiresAt) {
        this.downloadTicketExpiresAt = downloadTicketExpiresAt;
    }

    public LocalDateTime getQueuedAt() {
        return queuedAt;
    }

    public void setQueuedAt(LocalDateTime queuedAt) {
        this.queuedAt = queuedAt;
    }

    public LocalDateTime getReadyAt() {
        return readyAt;
    }

    public void setReadyAt(LocalDateTime readyAt) {
        this.readyAt = readyAt;
    }

    public LocalDateTime getReadyDeadline() {
        return readyDeadline;
    }

    public void setReadyDeadline(LocalDateTime readyDeadline) {
        this.readyDeadline = readyDeadline;
    }

    public LocalDateTime getTransferStartedAt() {
        return transferStartedAt;
    }

    public void setTransferStartedAt(LocalDateTime transferStartedAt) {
        this.transferStartedAt = transferStartedAt;
    }

    public LocalDateTime getTransferCompletedAt() {
        return transferCompletedAt;
    }

    public void setTransferCompletedAt(LocalDateTime transferCompletedAt) {
        this.transferCompletedAt = transferCompletedAt;
    }

    public LocalDateTime getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(LocalDateTime publishedAt) {
        this.publishedAt = publishedAt;
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
        return "FileTransfer{id=" + id + ", sessionId=" + sessionId + ", direction=" + direction
                + ", fileName=" + fileName + ", status=" + status
                + ", transferredBytes=" + transferredBytes + "/" + declaredSize + "}";
    }
}
