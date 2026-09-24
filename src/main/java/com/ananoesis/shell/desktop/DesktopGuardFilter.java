package com.ananoesis.shell.desktop;

import java.io.IOException;

import org.springframework.lang.Nullable;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 桌面守卫统一闸门（design D3）：tk 引导换票 + REST/WS 持票校验。
 *
 * <p>WHY 单个 Servlet Filter 同时管 REST 与 WS：WS 握手在协议升级前就是一个普通
 * HTTP GET，过滤器链先于 {@code WebSocketHandler} 执行——无票握手在这里就被 401 挡下，
 * 不必再写一个 {@code HandshakeInterceptor} 做重复判定。一处闸门，REST 与 WS 语义天然一致。</p>
 *
 * <p>守卫关闭（无启动令牌，Web/dev 形态）时本过滤器整体直通，既有行为零回归。</p>
 */
public class DesktopGuardFilter extends OncePerRequestFilter {

    /** 会话票 Cookie 名。HttpOnly，前端脚本不可读。 */
    static final String SESSION_COOKIE = "ananoesis_desktop_session";

    /** 引导查询参数名（壳 loadURL 携带，换票后即从 URL 剥离）。 */
    static final String TK_PARAM = "tk";

    /** 探活路径前缀：壳需要无凭据确认后端就绪。 */
    static final String HEALTH_PREFIX = "/actuator/health";

    private final DesktopGuard guard;
    private final DesktopSessionStore sessionStore;

    public DesktopGuardFilter(DesktopGuard guard, DesktopSessionStore sessionStore) {
        this.guard = guard;
        this.sessionStore = sessionStore;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!guard.enabled()) {
            filterChain.doFilter(request, response);
            return;
        }

        String path = pathOf(request);
        boolean isGet = "GET".equalsIgnoreCase(request.getMethod());

        // ① 探活：GET health 无需票（组件细节的收起由 EnvironmentPostProcessor 负责）
        if (isGet && path.startsWith(HEALTH_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        // ② 引导：GET 根路径带 tk → 校验通过换发 Cookie 并 302 去票；失败不发票
        if (isGet && "/".equals(path) && request.getParameter(TK_PARAM) != null) {
            handleBootstrap(request, response);
            return;
        }

        // ③ 其余一律（REST 与 WS 升级）要求携带有效会话票
        if (sessionStore.isValid(sessionCookieValue(request))) {
            filterChain.doFilter(request, response);
        } else {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        }
    }

    private void handleBootstrap(HttpServletRequest request, HttpServletResponse response) {
        String tk = request.getParameter(TK_PARAM);
        if (!guard.verifyLauncherToken(tk)) {
            // 错误票：401，不发任何 Set-Cookie，不回显令牌
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        String sessionId = sessionStore.issue();
        response.addHeader("Set-Cookie", buildCookie(sessionId));
        // 302 到去票后的干净根路径；Location 不含 tk 明文
        response.setStatus(HttpServletResponse.SC_FOUND);
        response.setHeader("Location", "/");
    }

    /**
     * 手工拼 Cookie 头而非用 {@code ResponseCookie}/{@code addCookie}。
     *
     * <p>WHY：Servlet 的 {@code Cookie.setHttpOnly} 在部分容器不下发 SameSite，
     * 而 {@code ResponseCookie} 又不经 {@code addHeader} 走；直接写头能确保
     * HttpOnly + SameSite=Strict 逐字落地，测试与浏览器读到同一张票。</p>
     */
    private String buildCookie(String sessionId) {
        return SESSION_COOKIE + "=" + sessionId + "; Path=/; HttpOnly; SameSite=Strict";
    }

    @Nullable
    private String sessionCookieValue(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (SESSION_COOKIE.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private String pathOf(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && uri.startsWith(context)) {
            uri = uri.substring(context.length());
        }
        return uri.isEmpty() ? "/" : uri;
    }
}
