package com.ananoesis.shell.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * SSH 模块的装配（design.md D4 双通道）。
 *
 * <p>WHY 单独一个配置类而不是把注解贴到 {@code SshConnectionService} 上：
 * {@code @EnableConfigurationProperties} 与 {@code @EnableScheduling} 是<b>模块级</b>决定
 * （"SSH 模块需要配置绑定"、"SSH 模块需要定时任务"），把它们放在服务实现上会让
 * "某个服务恰好被条件化排除"顺带关掉整个模块的配置绑定。沿用 Wave 1 里
 * {@code SqliteDataSourceConfiguration} 的做法，模块装配集中一处。</p>
 *
 * <p>{@code @EnableScheduling} 目前唯一的用途是终端会话的空闲回收
 * （{@code TerminalIdleReaper}）——task 6.6 的"超时释放资源"。
 * 用户直接关掉浏览器标签页时前端来不及发 {@code action=close}，
 * 没有这个定时器，被遗弃的 SSH 连接会一直挂到进程退出。</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SshProperties.class)
@EnableScheduling
public class SshConfiguration {
}
