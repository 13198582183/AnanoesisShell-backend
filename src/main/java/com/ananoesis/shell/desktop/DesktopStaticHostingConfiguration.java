package com.ananoesis.shell.desktop;

import java.nio.file.InvalidPathException;
import java.nio.file.Paths;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.Resource;
import org.springframework.lang.Nullable;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;
import org.springframework.web.servlet.resource.ResourceResolverChain;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 桌面形态的前端 dist 同源托管（design D2，任务 3.1）。
 *
 * <p>桌面壳 {@code loadURL(http://127.0.0.1:{port}/?tk=…)} 后，页面与其调用的
 * REST/WS 天然同源：零 CORS 配置、WS 既有同源限定原样生效、会话 Cookie 自动随行走。
 * dist 以<b>外置目录</b>挂载（{@code ananoesis.desktop.dist-dir}，由壳在启动参数里
 * 指向安装目录旁的 dist），不打进 jar —— 前端与后端的构建产物在组装期才汇合，
 * 各自仓库各自流水线互不越界。</p>
 *
 * <p>SPA 回退规则：静态文件命中优先；未命中且<b>不属于业务前缀</b>
 * （api/ws/actuator/internal）的 GET 一律回退 index.html 交给前端路由；
 * 业务前缀下未命中的路径保持原生 404/405 语义，绝不被回退吞成 200 HTML——
 * 那是契约面最恶劣的腐化（客户端拿 HTML 当 JSON 解析）。</p>
 */
@Configuration(proxyBeanMethods = false)
@Profile("desktop")
public class DesktopStaticHostingConfiguration implements WebMvcConfigurer {

    private static final Logger LOG = LoggerFactory.getLogger(DesktopStaticHostingConfiguration.class);

    /** 永不回退成 index.html 的业务前缀（对应 {@code /api}、{@code /ws}、{@code /actuator}、{@code /internal}）。 */
    private static final List<String> PROTECTED_PREFIXES = List.of("api", "ws", "actuator", "internal");

    private final String distDir;

    public DesktopStaticHostingConfiguration(@Value("${ananoesis.desktop.dist-dir:}") String distDir) {
        this.distDir = distDir;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = toResourceLocation(distDir);
        if (location == null) {
            // 桌面 profile 激活但没给 dist 目录（如集成测试之外的场景）：不托管，保持原生 404
            LOG.warn("desktop profile 已激活但 ananoesis.desktop.dist-dir 未配置，跳过前端静态托管");
            return;
        }
        registry.addResourceHandler("/**")
                .addResourceLocations(location)
                .resourceChain(false)
                .addResolver(new SpaFallbackResolver());
        LOG.info("桌面前端静态托管已挂载: {}", location);
    }

    /** 把本地路径转成 Spring 资源位置（file: URL，目录结尾带 /）；非法路径返回 null。 */
    @Nullable
    private static String toResourceLocation(String dir) {
        if (dir == null || dir.isBlank()) {
            return null;
        }
        try {
            String uri = Paths.get(dir).toAbsolutePath().normalize().toUri().toString();
            return uri.endsWith("/") ? uri : uri + "/";
        } catch (InvalidPathException e) {
            LOG.warn("ananoesis.desktop.dist-dir 不是合法路径，跳过静态托管: {}", dir);
            return null;
        }
    }

    /**
     * 未命中静态文件时的 SPA 回退解析器。
     *
     * <p>WHY 继承 {@link PathResourceResolver} 而不是再注册一个 {@code ErrorViewResolver}：
     * 回退逻辑与资源解析发生在同一趟 handler 里，命中文件优先、未命中才回退，
     * 一条规则一个位置，不存在两套机制互相遮蔽的暗坑。业务前缀在这里显式排除，
     * 让 {@code /api/**} 等的原生 404 语义原样穿透。</p>
     */
    static final class SpaFallbackResolver extends PathResourceResolver {

        @Override
        protected Resource resolveResourceInternal(@Nullable HttpServletRequest request,
                                                   String resourcePath,
                                                   List<? extends Resource> locations,
                                                   ResourceResolverChain chain) {
            Resource requested = super.resolveResourceInternal(request, resourcePath, locations, chain);
            if (requested != null) {
                return requested;
            }
            if (isProtectedPath(resourcePath)) {
                return null;
            }
            // 根路径与一切前端深路由：交给 index.html 由 SPA 自己的 router 决定视图
            return super.resolveResourceInternal(request, "index.html", locations, chain);
        }

        private boolean isProtectedPath(String resourcePath) {
            int segmentEnd = resourcePath.indexOf('/');
            String firstSegment = segmentEnd < 0 ? resourcePath : resourcePath.substring(0, segmentEnd);
            return PROTECTED_PREFIXES.contains(firstSegment);
        }
    }
}
