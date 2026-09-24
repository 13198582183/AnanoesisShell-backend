package com.ananoesis.shell.desktop;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * 桌面守卫装配（openspec 变更 add-desktop-client，design D3）。
 *
 * <p>骨架的开关判定（{@link DesktopGuard}）之上，任务 2.2 补齐运行时三件套：
 * 内存态票仓、tk 引导与 REST/WS 校验过滤器。守卫关闭（无启动令牌）时
 * 过滤器以 {@code enabled=false} 注册，不进入容器过滤链，Web/dev 形态零回归。</p>
 */
@Configuration(proxyBeanMethods = false)
public class DesktopGuardConfiguration {

    /**
     * 依据启动令牌判定守卫是否启用。
     *
     * <p>WHY 嵌套占位符 {@code ${property:${ENV:}}}：优先取显式 property
     * （测试与手工调试可用 {@code -D} 覆盖），缺省回落到壳注入的环境变量——
     * 生产语义保持"env 即开关"，与 design D3 一致。</p>
     *
     * @param launcherToken 生效的启动令牌；两处均未提供时为空串
     * @return 守卫判定 bean，供后续过滤器与测试注入
     */
    @Bean
    public DesktopGuard desktopGuard(
            @Value("${" + DesktopGuard.LAUNCHER_TOKEN_PROPERTY
                    + ":${" + DesktopGuard.LAUNCHER_TOKEN_ENV + ":}}") String launcherToken) {
        boolean enabled = !launcherToken.isBlank();
        return new DesktopGuard(enabled, enabled ? launcherToken : null);
    }

    /** 内存态会话票仓（进程重启即失效，见 {@link DesktopSessionStore}）。 */
    @Bean
    public DesktopSessionStore desktopSessionStore() {
        return new DesktopSessionStore();
    }

    /**
     * 停止触发器：异步闭上下文（{@code DesktopShutdownController} 依赖）。
     *
     * <p>守卫开关只决定端点是否受理（见控制器），不影响此 bean 存在；
     * 便于 {@code DesktopGuardFilter} 之外的组件统一拿到一个幂等的停止入口。</p>
     */
    @Bean
    public DesktopShutdownCoordinator desktopShutdownCoordinator(ConfigurableApplicationContext context) {
        return new ContextCloseShutdownCoordinator(context);
    }

    /**
     * 挂载守卫过滤器。
     *
     * <p>WHY {@code setEnabled(guard.enabled())} 而非 {@code @ConditionalOnProperty}：
     * 令牌可能来自环境变量，属性条件求值不便；直接按已判定的 {@link DesktopGuard} 开关
     * 控制注册最直白，关闭时过滤器完全不进链。</p>
     */
    @Bean
    public FilterRegistrationBean<DesktopGuardFilter> desktopGuardFilterRegistration(
            DesktopGuard guard, DesktopSessionStore sessionStore) {
        FilterRegistrationBean<DesktopGuardFilter> registration =
                new FilterRegistrationBean<>(new DesktopGuardFilter(guard, sessionStore));
        registration.setEnabled(guard.enabled());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
