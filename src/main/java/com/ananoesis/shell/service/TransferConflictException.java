package com.ananoesis.shell.service;

/**
 * 上传目标文件已存在且未确认覆盖（design.md D9 → 契约 {@code transfer_conflict} → HTTP 409）。
 *
 * <p>WHY 独立于 {@link ConflictException}：需要映射到专属错误码 {@code transfer_conflict}
 * 而非通用 {@code conflict}，前端据此弹出"文件已存在，是否覆盖？"的确认对话框。</p>
 */
public class TransferConflictException extends RuntimeException {

    /** 已存在目标的信息快照（类型/大小/mtime），供前端展示。 */
    private final String targetSnapshot;

    public TransferConflictException(String message, String targetSnapshot) {
        super(message);
        this.targetSnapshot = targetSnapshot;
    }

    public String getTargetSnapshot() {
        return targetSnapshot;
    }
}
