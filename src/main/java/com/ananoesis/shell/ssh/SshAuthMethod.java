package com.ananoesis.shell.ssh;

import java.util.Objects;

import com.ananoesis.shell.security.SecretText;

/**
 * SSH 认证方式与其明文凭据的内存载体。
 *
 * <p>WHY 用 sealed interface + record 而不是一个带三个可空字段的大对象：
 * "密码认证"与"私钥认证"所需的凭据完全不同，把它们塞进同一个 POJO 会让
 * "密码认证却带着私钥"这种非法状态可以被构造出来。sealed 让编译器替我们守住
 * "只有这两种认证方式"，也与契约 {@code AuthType} 的两个取值一一对应。</p>
 *
 * <p>WHY 字段是 {@link SecretText} 而非 {@code String}：
 * credential-store spec 要求明文只在内存中短暂存在且 MUST NOT 进日志。
 * {@code SecretText} 的 {@code toString()} 恒为掩码，且可被显式擦除；
 * record 的 {@code toString()} 会调用组件的 {@code toString()}，因此本类型
 * 天生就是"打印安全"的——这是选择 record 而非手写 getter 的一个附带收益。</p>
 *
 * <p>WHY 同时提供 {@code String} 与 {@code SecretText} 两套工厂：
 * {@code SecretText} 版本给 {@link CredentialBackedSshTargetResolver} 用，
 * 让解密结果**不经 String 中转**直接流入 SSH 层（String 不可擦除）；
 * {@code String} 版本给测试用，避免每个测试都手写擦除逻辑。</p>
 */
public sealed interface SshAuthMethod {

    /** 密码认证。 */
    static SshAuthMethod password(String password) {
        return new Password(SecretText.of(Objects.requireNonNull(password, "密码不得为 null")));
    }

    /** 密码认证（已由凭据服务解密，避免多余的不可擦除副本）。 */
    static SshAuthMethod password(SecretText password) {
        return new Password(Objects.requireNonNull(password, "密码不得为 null"));
    }

    /**
     * 私钥认证。
     *
     * @param privateKeyPem PEM 格式私钥全文（PKCS#8 / OpenSSH / PuTTY 均可，由 sshj 识别）
     * @param passphrase    私钥口令；未加密私钥传 {@code null} 或空白
     */
    static SshAuthMethod privateKey(String privateKeyPem, String passphrase) {
        SecretText key = SecretText.of(Objects.requireNonNull(privateKeyPem, "私钥不得为 null"));
        SecretText pass = (passphrase == null || passphrase.isBlank()) ? null : SecretText.of(passphrase);
        return new PrivateKey(key, pass);
    }

    /** 私钥认证（已由凭据服务解密）。{@code passphrase} 可为 {@code null}。 */
    static SshAuthMethod privateKey(SecretText privateKeyPem, SecretText passphrase) {
        return new PrivateKey(Objects.requireNonNull(privateKeyPem, "私钥不得为 null"), passphrase);
    }

    /** 擦除本对象持有的全部明文。幂等，可安全重复调用。 */
    void wipe();

    /** 密码认证凭据。 */
    record Password(SecretText password) implements SshAuthMethod {

        @Override
        public void wipe() {
            if (password != null) {
                password.wipe();
            }
        }
    }

    /**
     * 私钥认证凭据。
     *
     * @param passphrase 可为 {@code null}（未加密私钥）。
     *                   WHY 允许 null 而不是空 SecretText：空口令与"无口令"在
     *                   sshj 的 {@code PasswordFinder} 语义下不同——后者应当根本不设置 finder。
     */
    record PrivateKey(SecretText privateKey, SecretText passphrase) implements SshAuthMethod {

        @Override
        public void wipe() {
            if (privateKey != null) {
                privateKey.wipe();
            }
            if (passphrase != null) {
                passphrase.wipe();
            }
        }
    }
}
