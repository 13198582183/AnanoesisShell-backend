package com.ananoesis.shell.ssh;

import java.util.Objects;

/**
 * 一次 SSH 连接所需的全部信息（不可变快照）。
 *
 * <p>WHY 不让 SSH 层直接吃 {@code entity.Host} + 凭据：持久化实体的字段会随表结构演进，
 * 而连接逻辑需要的只是一组稳定坐标。以本记录作为边界，日后 {@code hosts} 加列
 * 不会波及 SSH 层，也让"凭据从哪来"这件事可以在测试里被替换成手写替身。</p>
 *
 * <p><b>安全</b>：本类型持有明文凭据（{@link SshAuthMethod}）。
 * {@link #toString()} 被显式覆写为只输出非敏感坐标，因此"顺手打一条日志"不会泄露私钥；
 * 用毕必须调用 {@link #wipeAuth()} 擦除。</p>
 */
public record SshTarget(String host, int port, String username, SshAuthMethod auth) {

    public SshTarget {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("host 不得为空");
        }
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("port 必须在 1..65535 之间，实际为 " + port);
        }
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username 不得为空");
        }
        Objects.requireNonNull(auth, "auth 不得为 null");
    }

    public static SshTarget of(String host, int port, String username, SshAuthMethod auth) {
        return new SshTarget(host, port, username, auth);
    }

    /** 擦除明文凭据。连接结束或失败后必须调用。 */
    public void wipeAuth() {
        auth.wipe();
    }

    /**
     * 只输出非敏感坐标。
     * WHY 显式覆写：record 的默认 {@code toString()} 会展开 {@code auth}，
     * 虽然 {@code SecretText} 自身掩码，但依赖"另一个类型恰好安全"是脆弱的组合；
     * 覆写后安全性由本类自己保证。
     */
    @Override
    public String toString() {
        return "SshTarget[host=" + host + ", port=" + port + ", username=" + username
                + ", auth=" + auth.getClass().getSimpleName() + "]";
    }
}
