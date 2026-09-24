package com.ananoesis.shell.desktop;

import java.net.URI;
import java.util.List;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import ch.qos.logback.core.read.ListAppender;
import com.ananoesis.shell.AbstractSqliteIntegrationTest;
import com.ananoesis.shell.config.WebSocketConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.DefaultUriBuilderFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 桌面守卫集成测试（任务 2.1，TDD RED）。
 *
 * <p>钉住 design D3 的五条行为契约：①无票 REST 401、②无票 WS 握手被拒、
 * ③合法 tk 引导换发 HttpOnly 同源 Cookie 且后续通行、④错误 tk 不换发票、
 * ⑤health 无票可读但组件细节收起。RED 阶段要求每条都<b>以断言失败</b>红掉
 * ——守卫 Filter/握手拦截器尚不存在，请求会拿到既有的 200/403/JSON-body-404，
 * 全是断言差异而非环境错误（上下文本身携带令牌可正常启动，见 {@link #guardIsEnabled}）。</p>
 *
 * <p>WHY 单独一个上下文指纹（{@code @TestPropertySource} 引入令牌）：
 * Spring 按配置指纹缓存上下文，守卫开启态与既有 772 用例的关闭态必须物理隔离，
 * 互不污染；代价是本次测试独占一次应用启动，可接受。</p>
 *
 * <p>WHY Cookie 断言不写死 Cookie 名：名字属于实现细节（2.2 定），
 * 行为契约只有"Set-Cookie 存在、HttpOnly、SameSite=Strict/Lax、且不含令牌明文"。</p>
 */
@TestPropertySource(properties = DesktopGuard.LAUNCHER_TOKEN_PROPERTY + "=it-desktop-guard-token-9f2c")
class DesktopGuardIntegrationTest extends AbstractSqliteIntegrationTest {

    /** 与 {@code @TestPropertySource} 一致的守卫令牌。 */
    private static final String TOKEN = "it-desktop-guard-token-9f2c";

    /** 故意错误的票；断言它不出现在任何响应里。 */
    private static final String WRONG_TOKEN = "wr0ng-token-DO-NOT-ECHO-7a";

    private static final ParameterizedTypeReference<String> PLAIN_BODY =
            new ParameterizedTypeReference<>() {
            };

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    /**
     * 不跟随重定向的裸 RestTemplate。
     *
     * <p>WHY：tk 引导的响应是 302 + Set-Cookie；跟随重定向会把这两者都吞掉，
     * 测试就必须反推而非直读。引导与换票的全部断言走这个客户端。
     * 容器注入的 {@link TestRestTemplate} 会跟随 302，故单独建 bean。</p>
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class NoRedirectConfig {

        @Bean
        RestTemplate noRedirectRestTemplate() {
            // JDK HttpClient 显式 NEVER_REDIRECT：302 + Set-Cookie 必须原样被测试读到
            JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                    java.net.http.HttpClient.newBuilder()
                            .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                            .build());
            RestTemplate template = new RestTemplate(factory);
            // 引导用例要直读任意 4xx 的状态码与头（错误票被拒也是响应），不得抛异常中断
            template.setErrorHandler(new DefaultResponseErrorHandler() {
                @Override
                public boolean hasError(ClientHttpResponse response) {
                    return false;
                }
            });
            template.setUriTemplateHandler(new DefaultUriBuilderFactory());
            return template;
        }
    }

    @Autowired
    @Qualifier("noRedirectRestTemplate")
    private RestTemplate noRedirectRest;

    /** 裸客户端不做相对路径展开（无容器基址注入），引导用例统一拼绝对 URI。 */
    private URI rootUri(String query) {
        return URI.create("http://localhost:" + port + "/" + query);
    }

    /** 前置自检：守卫上下文真的开启了（否则后面全是假绿/假红）。 */
    @Test
    @DisplayName("前置：携带令牌的上下文可启动，且 DesktopGuard Bean 为开启态")
    void guardIsEnabled() {
        // 这一条在 RED 阶段就应通过——它验证的是任务 1.3 的骨架，不是 2.2 的实现
        assertThat(rest.getForEntity("/actuator/health", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    // ==================================================================
    // ① 无票 REST 401
    // ==================================================================

    @Test
    @DisplayName("守卫开启：无 Cookie 的 REST 请求 401（现状 200 即 RED）")
    void restWithoutCookieIsUnauthorized() {
        ResponseEntity<String> response = rest.exchange("/api/hosts", HttpMethod.GET, null, PLAIN_BODY);

        assertThat(response.getStatusCode())
                .as("桌面形态下本机端口不再裸奔：无票 REST 必须 401")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("守卫开启：无票 POST /internal/shutdown 被过滤器 401 挡住（未到控制器）")
    void shutdownWithoutCookieIsUnauthorized() {
        ResponseEntity<String> response = rest.exchange("/internal/shutdown",
                HttpMethod.POST, null, PLAIN_BODY);

        // WHY：停止端点的"须持票"由同一道 Filter 保证（design D4 三重约束之一）；
        // 401 而非 404/202 证明它在票校验层就被拦下，根本走不到触发关闭。
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ==================================================================
    // ② 无票 WS 握手被拒
    // ==================================================================

    @Test
    @DisplayName("守卫开启：无 Cookie 的 WS 握手被拒（同源 Origin 下现状 403/101 即 RED）")
    void wsHandshakeWithoutCookieIsRejected() {
        HttpHeaders headers = handshakeHeaders("http://localhost:" + port);

        ResponseEntity<String> response = rest.exchange(WebSocketConfiguration.TERMINAL_ENDPOINT,
                HttpMethod.GET, new HttpEntity<>(headers), PLAIN_BODY);

        // WHY 用裸 HTTP 探状态码：Origin 已同源，此刻唯一变量是票。
        // 403（Origin 拦截）或升级成功都不是"票校验在起作用"，必须钉 401。
        assertThat(response.getStatusCode())
                .as("同源但无票的握手必须被票校验拒绝")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ==================================================================
    // ③ 合法 tk 引导 → HttpOnly Cookie → 后续通行
    // ==================================================================

    @Test
    @DisplayName("合法 tk 引导根路径：302 去票 + 换发 HttpOnly 同源 Cookie + 携票后续请求通行")
    void bootstrapWithValidTokenIssuesSessionCookie() {
        ResponseEntity<String> bootstrap = noRedirectRest.exchange(
                rootUri("?tk=" + TOKEN), HttpMethod.GET, null, PLAIN_BODY);

        assertThat(bootstrap.getStatusCode())
                .as("引导成功应重定向去掉 URL 票据（design D3）")
                .isEqualTo(HttpStatus.FOUND);
        List<String> setCookies = bootstrap.getHeaders().get(HttpHeaders.SET_COOKIE);
        assertThat(setCookies).as("引导必须换发一张会话 Cookie").isNotEmpty();
        String cookie = setCookies.get(0);
        assertThat(cookie).as("HttpOnly：页面脚本不得读票").contains("HttpOnly");
        assertThat(cookie.toUpperCase()).as("SameOrigin：Cookie 不外发跨源请求")
                .contains("SAMESITE=STRICT").doesNotContain("SAMESITE=NONE");
        assertThat(cookie).doesNotContain(TOKEN);
        assertThat(bootstrap.getHeaders().getLocation())
                .as("重定向目标不得再携带票据明文").extracting(URI::toString).satisfies(
                        loc -> assertThat(loc).doesNotContain(TOKEN));

        // 携票后续：REST 必须通行（200，而不是 401/403）
        HttpHeaders withCookie = new HttpHeaders();
        withCookie.add(HttpHeaders.COOKIE, cookie.substring(0, cookie.indexOf(';')));
        ResponseEntity<String> followUp = rest.exchange("/api/hosts", HttpMethod.GET,
                new HttpEntity<>(withCookie), PLAIN_BODY);
        assertThat(followUp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ==================================================================
    // ④ 错误 tk 不换发票
    // ==================================================================

    @Test
    @DisplayName("错误 tk 引导：被拒且响应不含任何 Set-Cookie（伪票换真票即 RED）")
    void bootstrapWithWrongTokenIssuesNoCookie() {
        ResponseEntity<String> response = noRedirectRest.exchange(
                rootUri("?tk=" + WRONG_TOKEN), HttpMethod.GET, null, PLAIN_BODY);

        assertThat(response.getStatusCode().is2xxSuccessful()
                || response.getStatusCode().is3xxRedirection())
                .as("错误票不得被引导接受").isFalse();
        assertThat(response.getHeaders().get(HttpHeaders.SET_COOKIE))
                .as("无论以何种状态码回应，错误票都不得换发 Cookie").isNull();
        // 401 回空 body（不回显令牌）；null 或不含令牌都算“未泄露”
        assertThat(response.getBody() == null || !response.getBody().contains(WRONG_TOKEN))
                .as("错误响应不得回显卡片令牌").isTrue();
    }

    // ==================================================================
    // ⑤ health 无票可读但细节收起
    // ==================================================================

    @Test
    @DisplayName("health 无票 GET：200 且只有 status，组件细节与数据路径不出现")
    void healthIsReadableWithoutTokenButRedacted() {
        ResponseEntity<String> response = rest.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).as("壳需要无凭据探活").isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\"").contains("UP");
        // WHY 拿组件名与路径关键词做负断言：show-details=never 后 components/DB 路径
        // 都不该出现（现状 always 会泄露 SQLite 数据目录，即本条 RED 的原因）
        assertThat(response.getBody())
                .doesNotContain("components")
                .doesNotContain("db")
                .doesNotContain(DATA_DIR.getFileName().toString());
    }

    private HttpHeaders handshakeHeaders(String origin) {
        HttpHeaders headers = new HttpHeaders();
        headers.setOrigin(origin);
        headers.setUpgrade("websocket");
        headers.setConnection("Upgrade");
        headers.set("Sec-WebSocket-Key", "dGhlIHNhbXBsZSBub25jZQ==");
        headers.set("Sec-WebSocket-Version", "13");
        return headers;
    }

    // ==================================================================
    // tk 掩码防线（任务 2.4）
    // ==================================================================

    @Test
    @DisplayName("tk 不泄露：引导请求走一遍，DEBUG 级全量日志无令牌明文")
    void bootstrapTokenNeverLeaksIntoLogs() {
        // WHY 把 root 调到 DEBUG：凭据防线的铁律是"在最啰嗦的级别上断言不泄露"（对齐
        // 三道 HTTP 日志防线与 TransferContentTicketLoggingTest 的做法）；tk 会短暂
        // 出现在 URL 查询串里，任何请求日志/异常链路把它带出去都是泄露。
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        Level originalLevel = root.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        try {
            root.setLevel(Level.DEBUG);
            ResponseEntity<String> ok = noRedirectRest.exchange(
                    rootUri("?tk=" + TOKEN), HttpMethod.GET, null, PLAIN_BODY);
            ResponseEntity<String> bad = noRedirectRest.exchange(
                    rootUri("?tk=" + WRONG_TOKEN), HttpMethod.GET, null, PLAIN_BODY);

            // 防假绿：用响应状态证明两条引导请求真的被守卫处理过（而非打错了地址）；
            // 不拿"日志非空"做证据——守卫当前对引导不打日志，那正是本条断言的一部分
            assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.FOUND);
            assertThat(bad.getStatusCode().value()).isNotEqualTo(200);

            String logs = capturedLogText(appender);
            assertThat(logs).doesNotContain(TOKEN).doesNotContain(WRONG_TOKEN);
        } finally {
            // 共享上下文与共享 LoggerContext：级别与 appender 必须复原，不污染其它用例
            root.detachAppender(appender);
            appender.stop();
            root.setLevel(originalLevel);
        }
    }

    /** 汇总被捕获日志：格式化消息 + 异常类名/消息 + 完整堆栈（泄露可能藏在任一处）。 */
    private String capturedLogText(ListAppender<ILoggingEvent> appender) {
        StringBuilder text = new StringBuilder();
        for (ILoggingEvent event : appender.list) {
            text.append(event.getFormattedMessage()).append('\n');
            IThrowableProxy proxy = event.getThrowableProxy();
            while (proxy != null) {
                text.append(proxy.getClassName()).append(": ").append(proxy.getMessage()).append('\n');
                for (StackTraceElementProxy element : proxy.getStackTraceElementProxyArray()) {
                    text.append(element).append('\n');
                }
                proxy = proxy.getCause();
            }
        }
        return text.toString();
    }
}
