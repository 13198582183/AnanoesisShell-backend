package com.ananoesis.shell.service;

import java.util.List;

/**
 * 业务规则层面的请求校验失败（契约 {@code ErrorCode.VALIDATION_ERROR} → HTTP 400）。
 *
 * <p>WHY 在 Bean Validation 之外还需要它：契约里有一类约束**无法**用 OpenAPI/JSR-380 表达，
 * 典型的是条件必填——{@code auth_type=password} 时 {@code password} 必填、
 * {@code auth_type=private_key} 时 {@code private_key} 必填。
 * 若放过，用户会保存出一台"永远连不上"的服务器配置，且界面上没有任何提示，
 * 直到第一次点连接才收到一个语焉不详的认证失败。</p>
 *
 * <p>WHY 携带字段级明细而非仅一条消息：契约的 {@code Error.details} 就是为此设计的，
 * 前端要把错误挂到具体表单项上；只给一句"参数不合法"等于让用户自己猜。</p>
 *
 * <p>安全约束：{@code field} 与 {@code reason} MUST NOT 含用户提交的明文取值。</p>
 */
public class InvalidRequestException extends RuntimeException {

    /**
     * 一处字段级违规。
     *
     * @param field  契约中的 JSON 字段名（如 {@code auth_type}），前端据此定位表单项
     * @param reason 人类可读原因，只描述规则不复述取值
     */
    public record FieldViolation(String field, String reason) {
    }

    private final List<FieldViolation> violations;

    public InvalidRequestException(String message, List<FieldViolation> violations) {
        super(message);
        // 复制一份：调用方持有的 List 若后续被修改，不应改变已抛出异常的语义
        this.violations = List.copyOf(violations);
    }

    /** 单字段违规的便捷构造。 */
    public InvalidRequestException(String field, String reason) {
        this("请求校验失败", List.of(new FieldViolation(field, reason)));
    }

    public List<FieldViolation> violations() {
        return violations;
    }
}
