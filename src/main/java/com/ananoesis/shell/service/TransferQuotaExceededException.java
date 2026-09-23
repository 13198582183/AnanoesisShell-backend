package com.ananoesis.shell.service;

/**
 * 传输配额已满（design.md D9 → 契约 {@code transfer_quota_exceeded} → HTTP 429）。
 *
 * <p>WHY 独立异常类型而非复用 {@link ConflictException}：429 与 409 的语义和前端处置完全不同。
 * 409 是"目标已存在，确认后可覆盖"；429 是"并发槽位用完，等一会儿再试"。
 * 混在一起前端无法区分该弹确认框还是该显示排队提示。</p>
 */
public class TransferQuotaExceededException extends RuntimeException {

    public TransferQuotaExceededException(String message) {
        super(message);
    }
}
