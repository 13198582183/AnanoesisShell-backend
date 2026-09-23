package com.ananoesis.shell.security;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Objects;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * "用户主密码派生密钥"回退路径（tasks 4.3）。
 *
 * <p>WHY 需要这条回退：credential-store spec 规定密钥库不可用时 MUST 明确报错、
 * 不得静默降级为明文。但"报错"只是底线，用户体验上还需要一条能继续工作的路——
 * 由用户主密码经 PBKDF2 派生出临时主密钥。它的信任来源从"操作系统账户"
 * 变成"用户记住的密码"，安全强度略低，但**始终是加密的**，绝不落到明文。</p>
 *
 * <p>WHY 选 PBKDF2WithHmacSHA256：JDK 内置、跨平台无需额外本地依赖。
 * Argon2/scrypt 抗 GPU 更强，但要引入第三方库与本地实现，
 * 对一个"回退路径"而言收益不抵供应链与可移植性成本。</p>
 *
 * <p>WHY 迭代次数默认 210000：OWASP 对 PBKDF2-HMAC-SHA256 给出的量级基线，
 * 在现代桌面 CPU 上单次派生约百毫秒级——对"保存/连接时各派生一次"的调用频率可接受。
 * 该值随密文信封一起落盘，将来上调不会让历史密文解不开。</p>
 */
public class MasterPasswordKeyDeriver {

    /** 写入密文信封 {@code kp} 字段的标识。 */
    public static final String ID = "master-password";

    /** PBKDF2-HMAC-SHA256 迭代次数默认值。 */
    public static final int DEFAULT_ITERATIONS = 210_000;

    /** 盐长度（字节）。16 字节足以让彩虹表与批量爆破失效。 */
    public static final int SALT_BYTES = 16;

    /** 派生密钥长度（位），对应 AES-256。 */
    public static final int KEY_BITS = 256;

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final SecureRandom RANDOM = new SecureRandom();

    /** @return 新的随机盐 */
    public byte[] newSalt() {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        return salt;
    }

    /**
     * 由主密码派生 AES-256 密钥。
     *
     * @param password   主密码；调用方负责用毕擦除（本方法不会保留引用）
     * @param salt       盐；必须与加密时一致（由密文信封携带）
     * @param iterations 迭代次数；必须与加密时一致（由密文信封携带）
     */
    public byte[] derive(char[] password, byte[] salt, int iterations) {
        Objects.requireNonNull(password, "主密码不得为 null");
        Objects.requireNonNull(salt, "盐不得为 null");
        if (iterations <= 0) {
            throw new IllegalArgumentException("迭代次数必须为正数，实际为 " + iterations);
        }
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, KEY_BITS);
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            // 消息只描述失败事实，不含密码、盐或任何派生中间值
            throw new CredentialCryptoException("主密码派生密钥失败", e);
        } finally {
            // PBEKeySpec 内部持有密码副本，必须显式清除
            spec.clearPassword();
        }
    }
}