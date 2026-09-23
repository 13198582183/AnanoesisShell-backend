package com.ananoesis.shell.security;

import java.util.HashMap;
import java.util.Map;

/**
 * 测试替身：内存版操作系统密钥库。
 *
 * <p>WHY 需要它：tasks 4.1 的验收是"单元测试能写入并读回主密钥"。真实 OS 密钥库
 * （Windows Credential Store / macOS Keychain / Linux SecretService）在无头 CI、
 * 容器或锁屏环境下不可用，直接对它断言会让测试变成"看机器脸色"的偶发失败。
 * 把密钥库抽象成 {@link OsKeyring} 后，契约测试在内存替身上确定性运行，
 * 真实后端另由 {@code JavaKeyringOsKeyringIT} 以假设门控方式验证。</p>
 *
 * <p>可切换为抛异常状态，用于覆盖 spec「密钥库不可用 MUST 明确报错」分支。</p>
 */
final class FakeOsKeyring implements OsKeyring {

    private final Map<String, String> entries = new HashMap<>();
    private volatile boolean failing;
    private volatile boolean closed;

    /** 让后续所有读写抛 {@link OsKeyringAccessException}，模拟后端不可用。 */
    void startFailing() {
        this.failing = true;
    }

    void stopFailing() {
        this.failing = false;
    }

    boolean isClosed() {
        return closed;
    }

    /** 直接窥视底层条目，用于断言"主密钥确实被写进了密钥库"。 */
    String peek(String service, String account) {
        return entries.get(key(service, account));
    }

    int size() {
        return entries.size();
    }

    @Override
    public String read(String service, String account) {
        guard();
        return entries.get(key(service, account));
    }

    @Override
    public void write(String service, String account, String secret) {
        guard();
        entries.put(key(service, account), secret);
    }

    @Override
    public void delete(String service, String account) {
        guard();
        entries.remove(key(service, account));
    }

    @Override
    public void close() {
        this.closed = true;
    }

    private void guard() {
        if (failing) {
            throw new OsKeyringAccessException("模拟的密钥库后端故障", null);
        }
    }

    private static String key(String service, String account) {
        return service + "\u0000" + account;
    }
}
