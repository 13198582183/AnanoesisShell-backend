package com.ananoesis.shell.security;

/**
 * 凭据保护体系不可用。
 *
 * <p>对应 credential-store spec 的 MUST 级要求：「在密钥库不可用时 MUST 明确报错，
 * 不得静默降级为明文存储」。因此本异常表达的不是"加密算法出错"，
 * 而是"保护机制本身缺失"——遇到它，系统必须**拒绝**保存凭据并向用户报告，
 * 而不是退化成明文写库。</p>
 *
 * <p>安全约束：消息中 MUST NOT 出现明文凭据、主密钥或密文内容。</p>
 */
public class CredentialProtectionException extends RuntimeException {

    /** spec 规定的用户可见提示语；测试会断言它出现在消息与日志中。 */
    public static final String UNAVAILABLE_MESSAGE = "凭据保护不可用";

    public CredentialProtectionException(String message) {
        super(message);
    }

    public CredentialProtectionException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * 构造"凭据保护不可用"异常。
     *
     * @param detail 面向排障的补充说明，MUST NOT 含秘密
     * @param cause  底层原因，可为 null
     */
    public static CredentialProtectionException unavailable(String detail, Throwable cause) {
        return new CredentialProtectionException(UNAVAILABLE_MESSAGE + "：" + detail, cause);
    }
}