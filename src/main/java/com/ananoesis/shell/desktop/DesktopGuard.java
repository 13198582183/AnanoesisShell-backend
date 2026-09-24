package com.ananoesis.shell.desktop;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.lang.Nullable;

/**
 * 桌面守卫判定结果（openspec 变更 add-desktop-client，design D3）。
 *
 * <p>语义：仅当桌面壳经环境变量 {@code ANANOESIS_LAUNCHER_TOKEN} 注入了非空
 * 启动令牌时，守卫才生效——本机 REST 与 WS 端点随即要求持票访问。
 * 没有该 env（Web/dev 形态）守卫保持关闭，既有行为零回归。</p>
 *
 * @param enabled       守卫是否启用（token 非空白即启用）
 * @param launcherToken 壳注入的启动令牌；守卫关闭时为 null
 */
public record DesktopGuard(boolean enabled, @Nullable String launcherToken) {

    /** 壳注入后端的启动令牌环境变量名（同时可被同名 Spring property 覆盖，便于测试注入）。 */
    public static final String LAUNCHER_TOKEN_ENV = "ANANOESIS_LAUNCHER_TOKEN";

    /** 令牌对应的 Spring property 名；测试经 @SpringBootTest properties 注入模拟桌面形态。 */
    public static final String LAUNCHER_TOKEN_PROPERTY = "ananoesis.desktop.launcher-token";

    /**
     * 常数时间比较候选令牌与启动令牌。
     *
     * <p>WHY 常数时间：这是本机端口唯一的外部鉴权判定；{@code String.equals}
     * 会在首个不同字符短路，理论上给了逐字符爆破 token 的时序侧信道。
     * {@link MessageDigest#isEqual} 对字节数组做定长比较，是 JDK 内置的标准做法。</p>
     *
     * @param candidate 待校验的候选值（来自引导请求的 tk 查询参数）
     * @return 守卫启用且候选与启动令牌一致时为 true
     */
    public boolean verifyLauncherToken(@Nullable String candidate) {
        if (!enabled || launcherToken == null || candidate == null) {
            return false;
        }
        return MessageDigest.isEqual(
                launcherToken.getBytes(StandardCharsets.UTF_8),
                candidate.getBytes(StandardCharsets.UTF_8));
    }
}
