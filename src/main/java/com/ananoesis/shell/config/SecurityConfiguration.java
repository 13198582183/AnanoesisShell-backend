package com.ananoesis.shell.config;

import java.util.List;

import com.ananoesis.shell.security.CredentialCryptoService;
import com.ananoesis.shell.security.JavaKeyringOsKeyring;
import com.ananoesis.shell.security.MasterKeyProvider;
import com.ananoesis.shell.security.MasterPasswordKeyDeriver;
import com.ananoesis.shell.security.OsKeyring;
import com.ananoesis.shell.security.OsKeyringMasterKeyStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 凭据安全装配（credential-store spec：主密钥由操作系统密钥库保护）。
 *
 * <p>WHY 密钥库缺失时**不**让应用启动失败：
 * spec 要求的是"使用时明确报错、禁止静默明文"，而不是"没有密钥库就不许启动"。
 * 让应用照常起来，用户才能进入设置页改用主密码回退——把启动变成硬门禁，
 * 等于把唯一的自救通道也一起锁死了。</p>
 *
 * <p>WHY 用属性开关而非 {@code @ConditionalOnMissingBean}：
 * 后者的判定依赖 bean 定义的注册顺序，在普通 {@code @Configuration} 中并不可靠
 * （Spring 官方仅保证它在自动配置类中的行为）。测试需要确定性地屏蔽真实密钥库，
 * 属性开关是唯一无歧义的做法。</p>
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(SecurityConfiguration.class);

    /**
     * 打开当前平台的 OS 密钥库；无可用后端时返回一个恒定不可用的实现。
     *
     * <p>Bean 由 Spring 在上下文关闭时自动调用 {@link OsKeyring#close()}
     * （因为 {@code OsKeyring} 继承 {@code AutoCloseable}），无需显式声明 destroyMethod。</p>
     */
    @Bean
    @ConditionalOnProperty(prefix = "ananoesis.security.os-keyring", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public OsKeyring osKeyring() {
        OsKeyring keyring = JavaKeyringOsKeyring.open();
        if (keyring == null) {
            // WHY 只告警不抛异常：见类注释——必须给用户留下走主密码回退的机会
            LOG.warn("当前平台无可用操作系统密钥库后端；凭据加密将不可用，仅可使用用户主密码派生密钥回退");
            return OsKeyring.unavailable("当前平台无可用操作系统密钥库后端");
        }
        LOG.info("已接入操作系统密钥库用于托管凭据主密钥");
        return keyring;
    }

    @Bean
    @ConditionalOnProperty(prefix = "ananoesis.security.os-keyring", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public MasterKeyProvider osKeyringMasterKeyProvider(OsKeyring osKeyring) {
        return new OsKeyringMasterKeyStore(osKeyring);
    }

    @Bean
    public MasterPasswordKeyDeriver masterPasswordKeyDeriver() {
        return new MasterPasswordKeyDeriver();
    }

    /**
     * @param providers 容器中所有主密钥来源。
     *                  WHY 用 {@link ObjectProvider} 而非 {@code List<MasterKeyProvider>}：
     *                  当没有任何来源被注册时（例如测试里显式关闭、且未提供替身），
     *                  {@code List} 注入会因"找不到候选 bean"而启动失败，
     *                  {@code ObjectProvider} 则给出空流，让"无可用密钥"退化为运行期的明确报错。
     */
    @Bean
    public CredentialCryptoService credentialCryptoService(ObjectProvider<MasterKeyProvider> providers,
                                                           MasterPasswordKeyDeriver deriver) {
        List<MasterKeyProvider> resolved = providers.orderedStream().toList();
        LOG.info("凭据加解密服务已就绪，主密钥来源数量={}", resolved.size());
        return new CredentialCryptoService(resolved, deriver, MasterPasswordKeyDeriver.DEFAULT_ITERATIONS);
    }
}