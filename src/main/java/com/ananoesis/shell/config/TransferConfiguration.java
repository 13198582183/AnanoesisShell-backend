package com.ananoesis.shell.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 文件传输模块装配（design.md D9）。
 *
 * <p>WHY 独立配置类：遵循已有 {@link SshConfiguration} 的模块级装配模式，
 * 把 {@link TransferProperties} 的 {@code @EnableConfigurationProperties} 放在此处，
 * 避免污染数据源或 SSH 配置。</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TransferProperties.class)
public class TransferConfiguration {
}
