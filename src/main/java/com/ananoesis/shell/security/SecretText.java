package com.ananoesis.shell.security;

import java.util.Arrays;
import java.util.Objects;

/**
 * 明文凭据的内存载体：打印即掩码、用完即擦除。
 *
 * <p>WHY 需要它（credential-store spec：MUST NOT 将明文凭据写入日志、审计记录或错误信息）：
 * Java 的 {@code String} 不可变，一旦创建就无法从堆上抹掉，而且极易被日志框架、
 * 异常消息、调试器甚至一次 {@code toString()} 顺手打印出来。把明文收进本类型后，
 * "不泄露"从一条需要人时刻警惕的纪律，变成了类型自带的默认属性。</p>
 *
 * <p>WHY 用 {@code char[]} 而非 {@code byte[]}：sshj 的 {@code PasswordFinder} 等
 * SSH 侧 API 直接消费 {@code char[]}，用同一形态可避免为了传递而多产生一份
 * 无法擦除的 {@code String} 中间副本。</p>
 *
 * <p>典型用法是 try-with-resources，作用域结束即擦除：
 * <pre>{@code
 * try (SecretText password = store.find(HOST, hostId, SSH_PASSWORD).orElseThrow()) {
 *     session.authPassword(username, password.revealChars());
 * }
 * }</pre></p>
 *
 * <p>WHY 刻意不实现 {@code equals}/{@code hashCode}：基于秘密值的相等性比较
 * 会把明文的指纹带进 HashMap 与断言失败消息；本类型采用身份语义，
 * 需要比较内容时由调用方显式 {@link #revealAsString()}。</p>
 */
public final class SecretText implements AutoCloseable {

    /** 打印凭据对象时唯一允许出现的文本。测试会断言日志中只出现它。 */
    public static final String MASK = "***MASKED***";

    private char[] value;
    private boolean wiped;

    private SecretText(char[] value) {
        this.value = value;
    }

    /**
     * 由字符串构造。
     *
     * <p>注意：入参 {@code String} 本身已无法擦除，这是 Java 平台的既有限制。
     * 因此调用方应尽可能让明文以 {@code char[]} 形态产生（如从密码输入框直读），
     * 本工厂主要服务于测试与不得不经过 String 的第三方 API 边界。</p>
     */
    public static SecretText of(String value) {
        Objects.requireNonNull(value, "凭据明文不得为 null；'不存在'请用 Optional.empty() 表达");
        return new SecretText(value.toCharArray());
    }

    /** 由字符数组构造；会**复制**入参，调用方擦除自己的副本不影响本对象。 */
    public static SecretText of(char[] value) {
        Objects.requireNonNull(value, "凭据明文不得为 null；'不存在'请用 Optional.empty() 表达");
        return new SecretText(value.clone());
    }

    /** @return 明文的**副本**；调用方用毕有责任自行擦除 */
    public char[] revealChars() {
        ensureReadable();
        return value.clone();
    }

    /**
     * @return 明文的 {@code String} 形态。
     *         WHY 单独提供：Spring AI 等第三方 API 只接受 String。
     *         调用它意味着放弃可擦除性，应把结果限制在尽可能小的作用域内。
     */
    public String revealAsString() {
        ensureReadable();
        return new String(value);
    }

    public boolean isWiped() {
        return wiped;
    }

    /** 清零内部缓冲。幂等。 */
    public void wipe() {
        if (!wiped) {
            Arrays.fill(value, '\0');
            value = null;
            wiped = true;
        }
    }

    @Override
    public void close() {
        wipe();
    }

    /** 永远返回掩码——这是本类型存在的核心理由，MUST NOT 被覆盖或绕过。 */
    @Override
    public String toString() {
        return MASK;
    }

    private void ensureReadable() {
        if (wiped) {
            // WHY 抛异常而非返回空串：擦除后再读取一定是调用时序 bug
            // （例如在 try-with-resources 之外使用了凭据），静默返回空值会把它
            // 伪装成"密码是空的"这类极难排查的认证失败。
            throw new IllegalStateException("凭据已被擦除，无法再读取；请检查其作用域是否已退出 try-with-resources");
        }
    }
}