package com.ananoesis.shell.desktop;

import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * 桌面守卫联动配置：守卫启用时强制健康端点不展组件细节。
 *
 * <p>WHY 必须是 EnvironmentPostProcessor：{@code management.endpoint.health.show-details}
 * 由 Actuator 自动配置在上下文早期消费，普通 bean 初始化时再改 property 已无机会；
 * 而 application.yml 现有值为 {@code always}（把 SQLite/Flyway 细节给排障者），
 * 只有插到 property sources <b>首位</b>才能既覆盖 yml、又不挡用户显式配置
 * ——桌面形态下把组件细节暴露给任何无票探活者等于泄露数据目录路径（design D3）。</p>
 *
 * <p>WHY 判定读 property 而非直接读 {@code System.getenv}：Spring 的
 * systemEnvironment property source 让二者在 {@code getProperty} 下统一，
 * 测试也可以经同名 property 注入模拟，无需伪造进程环境。</p>
 */
public class DesktopGuardEnvironmentPostProcessor implements EnvironmentPostProcessor {

    /** 注入的 property source 名；测试与排障按此名定位。 */
    static final String PROPERTY_SOURCE_NAME = "desktopGuardDefaults";

    /** 被强制覆盖的健康细节开关。 */
    static final String HEALTH_SHOW_DETAILS = "management.endpoint.health.show-details";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String token = environment.getProperty(DesktopGuard.LAUNCHER_TOKEN_PROPERTY);
        if (token == null || token.isBlank()) {
            token = environment.getProperty(DesktopGuard.LAUNCHER_TOKEN_ENV);
        }
        if (token != null && !token.isBlank()) {
            // 桌面守卫开启：探活者无票，健康响应只留 status，组件细节一律收起
            environment.getPropertySources().addFirst(new MapPropertySource(
                    PROPERTY_SOURCE_NAME, Map.of(HEALTH_SHOW_DETAILS, "never")));
        }
    }
}
