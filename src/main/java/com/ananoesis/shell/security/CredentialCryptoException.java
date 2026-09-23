package com.ananoesis.shell.security;

/**
 * 加解密过程失败：密文被篡改、主密钥不匹配、信封格式非法等。
 *
 * <p>WHY 与 {@link CredentialProtectionException} 分开：后者表示"保护机制缺失"，
 * 用户需要去修环境或改用主密码回退；本异常表示"机制在，但这份密文解不开"，
 * 通常意味着数据被改动或密钥被更换。两者的处置动作完全不同，合并会让前端无法分流提示。</p>
 *
 * <p>安全约束：消息与 cause 链中 MUST NOT 出现明文凭据。GCM 的认证失败本身
 * 也只会给出"标签不匹配"这类无信息量的结论，不存在泄露明文的通道。</p>
 */
public class CredentialCryptoException extends RuntimeException {

    public CredentialCryptoException(String message) {
        super(message);
    }

    public CredentialCryptoException(String message, Throwable cause) {
        super(message, cause);
    }
}