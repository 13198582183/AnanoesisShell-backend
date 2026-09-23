package com.ananoesis.shell.service;

/**
 * 请求的资源不存在（契约 {@code ErrorCode.NOT_FOUND} → HTTP 404）。
 *
 * <p>WHY 需要统一的基类：Wave 2 之后会有 host / model_config / conversation / approval
 * 等多种资源，各自的"不存在"都要映射到同一个 404 + {@code not_found}。
 * 若让 {@code ApiExceptionHandler} 逐个列举异常类型，每加一种资源就要改一次映射表，
 * 而漏改的后果是 500——一个"查不到记录"被报成"服务器崩了"。</p>
 *
 * <p>安全约束：消息由服务端自行构造，MUST NOT 拼接用户提交的凭据类内容；
 * 只允许出现 id、类型名这类坐标信息。</p>
 */
public abstract class NotFoundException extends RuntimeException {

    protected NotFoundException(String message) {
        super(message);
    }
}
