package com.ananoesis.shell.security;

/**
 * 凭据种类，对应 {@code credentials.credential_type} 的 CHECK 约束取值。
 *
 * <p>WHY 种类必须落库：审计需要在**不解密**的前提下回答"这台机器存了密码还是私钥"，
 * 解密时也据此决定明文的用法（密码喂给 {@code authPassword}，
 * PEM 私钥喂给 {@code authPublickey}，api key 喂给模型客户端）。</p>
 */
public enum CredentialType {

    /** SSH 登录密码。 */
    SSH_PASSWORD("ssh_password"),
    /** SSH 私钥（PEM 全文）。 */
    SSH_PRIVATE_KEY("ssh_private_key"),
    /** 私钥的 passphrase。 */
    SSH_PASSPHRASE("ssh_passphrase"),
    /** 大模型 api key。 */
    LLM_API_KEY("llm_api_key"),
    /** 预留的通用秘密，避免为新增种类频繁改表。 */
    GENERIC_SECRET("generic_secret");

    private final String columnValue;

    CredentialType(String columnValue) {
        this.columnValue = columnValue;
    }

    /** @return 落库使用的字面值 */
    public String columnValue() {
        return columnValue;
    }

    /** 由库中字面值反查枚举。 */
    public static CredentialType fromColumnValue(String value) {
        for (CredentialType type : values()) {
            if (type.columnValue.equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知的凭据类型: " + value);
    }
}