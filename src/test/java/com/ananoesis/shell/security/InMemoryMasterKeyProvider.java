package com.ananoesis.shell.security;

import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 测试替身：内存中的主密钥提供者。
 *
 * <p>WHY 不用 Mockito：主密钥提供者的契约极窄（三个方法），手写替身既能表达意图，
 * 又不会把测试与 mock 框架的字节码注入机制绑在一起——JDK 后续版本将默认禁止动态挂载 agent，
 * 依赖 mock 的测试会成为未来的构建风险点。</p>
 *
 * <p>可切换为"不可用"状态，用于验证 credential-store spec「密钥库不可用 → 明确报错、
 * 禁止静默降级为明文」这一 MUST 级要求。</p>
 */
final class InMemoryMasterKeyProvider implements MasterKeyProvider {

    static final String ID = "os-keyring";

    private final byte[] key;
    private final AtomicInteger readCount = new AtomicInteger();
    private volatile boolean available = true;

    InMemoryMasterKeyProvider() {
        this(32);
    }

    InMemoryMasterKeyProvider(int keyBytes) {
        this.key = new byte[keyBytes];
        new SecureRandom().nextBytes(this.key);
    }

    /** 模拟密钥库失联（如 Linux 上 SecretService 未运行）。 */
    void markUnavailable() {
        this.available = false;
    }

    void markAvailable() {
        this.available = true;
    }

    int readCount() {
        return readCount.get();
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    @Override
    public byte[] copyMasterKeyBytes() {
        readCount.incrementAndGet();
        if (!available) {
            throw CredentialProtectionException.unavailable("内存主密钥提供者已被标记为不可用", null);
        }
        return key.clone();
    }
}
