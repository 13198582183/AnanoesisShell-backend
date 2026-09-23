package com.ananoesis.shell.security;

/**
 * 尚未配置大模型 api key 却尝试使用 AI 能力。
 *
 * <p>对应 credential-store spec「API Key 不硬编码」的 Scenario：
 * MUST NOT 退回任何内置默认值，而是提示「请先在设置中配置模型 api key」。
 * 把这句话固化成异常类型而非散落的字符串，是为了让前端能稳定识别并跳转到设置页。</p>
 */
public class MissingModelApiKeyException extends RuntimeException {

    /** spec 指定的提示语原文，不得改写。 */
    public static final String MESSAGE = "请先在设置中配置模型 api key";

    public MissingModelApiKeyException() {
        super(MESSAGE);
    }

    /** @param detail 面向排障的定位信息（如 modelConfigId），MUST NOT 含秘密 */
    public MissingModelApiKeyException(String detail) {
        super(MESSAGE + "（" + detail + "）");
    }
}