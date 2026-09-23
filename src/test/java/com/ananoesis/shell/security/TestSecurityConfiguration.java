package com.ananoesis.shell.security;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * 集成测试用的凭据安全装配。
 *
 * <p>WHY 必须替换掉真实的 OS 密钥库提供者：集成测试若在开发者机器上真的往
 * Windows Credential Store / macOS Keychain 写入条目，会产生测试残留、
 * 并在 CI 或锁屏环境下直接失败。生产装配通过
 * {@code ananoesis.security.os-keyring.enabled=false}（由
 * {@link com.ananoesis.shell.AbstractSqliteIntegrationTest} 注入）关闭真实提供者，
 * 本配置补上一个确定性的内存提供者。</p>
 *
 * <p>该内存提供者持有**进程内固定**的随机主密钥：同一次测试运行内加解密自洽，
 * 且不同运行之间密钥不同，可顺带暴露"把密钥硬编码"之类的回归。</p>
 */
@TestConfiguration
public class TestSecurityConfiguration {

    /** 供测试断言复用的同一实例（例如校验读取次数）。 */
    static final InMemoryMasterKeyProvider SHARED_PROVIDER = new InMemoryMasterKeyProvider();

    @Bean
    MasterKeyProvider testMasterKeyProvider() {
        return SHARED_PROVIDER;
    }
}
