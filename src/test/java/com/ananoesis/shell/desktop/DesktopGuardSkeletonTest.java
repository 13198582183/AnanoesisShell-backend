package com.ananoesis.shell.desktop;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 桌面守卫骨架测试（任务 1.3）。
 *
 * <p>WHY 用 {@link ApplicationContextRunner} 而非全量 {@code @SpringBootTest}：
 * 骨架只验证"开关判定与 bean 装配在两种上下文下都能成立"，轻量上下文
 * 不触发 SQLite/Flyway/WS 全套自动配置，也不会给 Spring 上下文缓存添新指纹；
 * 守卫开启态的完整应用启动由任务 2.1 的 RED 集成测试覆盖。</p>
 */
class DesktopGuardSkeletonTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(DesktopGuardConfiguration.class);

    @Test
    @DisplayName("无启动令牌：守卫关闭且上下文可正常启动（Web/dev 形态零回归前提）")
    void guardDisabledWithoutToken() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            DesktopGuard guard = context.getBean(DesktopGuard.class);
            assertThat(guard.enabled()).isFalse();
            assertThat(guard.launcherToken()).isNull();
        });
    }

    @Test
    @DisplayName("property 注入启动令牌：守卫开启，bean 携带令牌")
    void guardEnabledWithTokenProperty() {
        runner.withPropertyValues(DesktopGuard.LAUNCHER_TOKEN_PROPERTY + "=a3f9-test-token")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    DesktopGuard guard = context.getBean(DesktopGuard.class);
                    assertThat(guard.enabled()).isTrue();
                    assertThat(guard.launcherToken()).isEqualTo("a3f9-test-token");
                });
    }

    @Test
    @DisplayName("verifyLauncherToken 只认精确匹配；null/空/错值一律拒")
    void tokenVerificationIsExact() {
        DesktopGuard guard = new DesktopGuard(true, "expected-token");
        assertThat(guard.verifyLauncherToken("expected-token")).isTrue();
        assertThat(guard.verifyLauncherToken("Expected-token")).isFalse();
        assertThat(guard.verifyLauncherToken("expected-toke")).isFalse();
        assertThat(guard.verifyLauncherToken("")).isFalse();
        assertThat(guard.verifyLauncherToken(null)).isFalse();
        assertThat(new DesktopGuard(false, null).verifyLauncherToken("expected-token")).isFalse();
    }

    @Test
    @DisplayName("EPP：守卫开启时首位注入 show-details=never；关闭时不动 property sources")
    void environmentPostProcessorForcesHealthPrivacy() {
        MockEnvironment withToken = new MockEnvironment();
        withToken.setProperty(DesktopGuard.LAUNCHER_TOKEN_PROPERTY, "tok-123");
        new DesktopGuardEnvironmentPostProcessor()
                .postProcessEnvironment(withToken, (SpringApplication) null);
        assertThat(withToken.getProperty("management.endpoint.health.show-details")).isEqualTo("never");
        assertThat(withToken.getPropertySources().contains(
                DesktopGuardEnvironmentPostProcessor.PROPERTY_SOURCE_NAME)).isTrue();

        MockEnvironment withoutToken = new MockEnvironment();
        new DesktopGuardEnvironmentPostProcessor()
                .postProcessEnvironment(withoutToken, (SpringApplication) null);
        assertThat(withoutToken.getPropertySources().contains(
                DesktopGuardEnvironmentPostProcessor.PROPERTY_SOURCE_NAME)).isFalse();
    }

    @Test
    @DisplayName("EPP：空白令牌不视为守卫开启（防止壳传参疏漏造成锁死）")
    void blankTokenKeepsGuardOff() {
        MockEnvironment blank = new MockEnvironment();
        blank.setProperty(DesktopGuard.LAUNCHER_TOKEN_PROPERTY, "   ");
        new DesktopGuardEnvironmentPostProcessor()
                .postProcessEnvironment(blank, (SpringApplication) null);
        assertThat(blank.getPropertySources().contains(
                DesktopGuardEnvironmentPostProcessor.PROPERTY_SOURCE_NAME)).isFalse();
    }

    /** 常量哨兵：防止误改属性名——property 名同时被配置类与壳侧（design D3）引用。 */
    @Test
    @DisplayName("令牌属性与环境变量命名契约")
    void tokenNamingContract() {
        assertThat(Map.of(
                DesktopGuard.LAUNCHER_TOKEN_PROPERTY, "ananoesis.desktop.launcher-token",
                DesktopGuard.LAUNCHER_TOKEN_ENV, "ANANOESIS_LAUNCHER_TOKEN"))
                .containsEntry("ananoesis.desktop.launcher-token", "ananoesis.desktop.launcher-token")
                .containsEntry("ANANOESIS_LAUNCHER_TOKEN", "ANANOESIS_LAUNCHER_TOKEN");
    }
}
