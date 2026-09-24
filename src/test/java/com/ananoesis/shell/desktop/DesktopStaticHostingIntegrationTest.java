package com.ananoesis.shell.desktop;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.ananoesis.shell.AbstractSqliteIntegrationTest;
import com.ananoesis.shell.config.WebSocketConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 桌面静态托管与 SPA 回退集成测试（任务 3.1）。
 *
 * <p>design D2：桌面形态下前端 dist 由后端同源托管，壳只当浏览器壳。本类在
 * {@code desktop} profile 下验证三件事——①GET 根路径与任意非 API 路径回退到 index.html、
 * ②{@code /api}、{@code /ws}、{@code /actuator}、{@code /internal} 前缀不被回退遮蔽、
 * ③真实契约端点语义不变（各抽一个证明 200/405/400 而非被吞成 index.html）。</p>
 *
 * <p>WHY 本类不注入启动令牌（守卫关闭）：静态托管与授权守卫是两条正交开关（profile vs env）。
 * 关掉守卫才能隔离验证"回退不遮蔽业务前缀"本身——若同时开守卫，未持票请求会先被 401 挡在
 * 过滤器层，静态行为根本走不到，测试就测不到想测的东西。守卫开启态的静态可达性由任务 4.1 实机冒烟覆盖。</p>
 */
@ActiveProfiles("desktop")
@TestPropertySource(properties = "ananoesis.desktop.launcher-token=")
class DesktopStaticHostingIntegrationTest extends AbstractSqliteIntegrationTest {

    /** 回退目标页里放的哨兵标记，断言"返回的确实是 index.html"。 */
    private static final String INDEX_SENTINEL = "<!--ANANOESIS_DESKTOP_INDEX_SENTINEL-->";

    /** 本次测试合成的前端 dist 目录（真实 dist 在任务 5.x 组装）。 */
    private static Path distDir;

    @Autowired
    private TestRestTemplate rest;

    @DynamicPropertySource
    static void pointStaticAtTempDist(DynamicPropertyRegistry registry) {
        registry.add("ananoesis.desktop.dist-dir", () -> distDir.toString());
    }

    @BeforeAll
    static void writeFakeDist() throws IOException {
        distDir = DATA_DIR.resolve("desktop-dist");
        Files.createDirectories(distDir);
        Files.writeString(distDir.resolve("index.html"),
                INDEX_SENTINEL + "\n<!doctype html><html><head><title>AnanoesisShell</title></head>"
                        + "<body><div id=app></div></body></html>");
        // 一个静态资产，证明"命中的真实文件优先于回退"
        Files.writeString(distDir.resolve("app.js"), "console.log('ananoesis');");
    }

    @Test
    @DisplayName("GET / 返回 index.html（同源托管根）")
    void rootServesIndexHtml() {
        ResponseEntity<String> response = rest.getForEntity("/", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // MediaType.isCompatibleWith 是实例方法（允许带 charset 参数），不是 AssertJ 断言方法
        MediaType contentType = response.getHeaders().getContentType();
        assertThat(contentType).isNotNull();
        assertThat(contentType.isCompatibleWith(MediaType.TEXT_HTML)).isTrue();
        assertThat(response.getBody()).contains(INDEX_SENTINEL);
    }

    @Test
    @DisplayName("GET 真实静态文件返回其内容而非 index.html 回退")
    void realAssetTakesPrecedenceOverFallback() {
        ResponseEntity<String> response = rest.getForEntity("/app.js", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("ananoesis");
        assertThat(response.getBody()).doesNotContain(INDEX_SENTINEL);
    }

    @Test
    @DisplayName("GET 任意非 API 深路径回退 index.html（SPA 前端路由）")
    void unknownClientRouteFallsBackToIndex() {
        ResponseEntity<String> response = rest.getForEntity("/workspaces/42/terminal", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains(INDEX_SENTINEL);
    }

    // ==================================================================
    // ② 业务前缀不被回退遮蔽（各抽一个真实端点证明语义不变）
    // ==================================================================

    @Test
    @DisplayName("契约端点 /api/hosts 不被回退吞掉：仍是 JSON 200 而非 index.html")
    void apiPrefixNotShadowed() {
        ResponseEntity<String> response = rest.getForEntity("/api/hosts", String.class);

        // 守卫关闭态下 /api/hosts 正常返回 JSON 列表；关键是它没被 SPA 回退变成 HTML
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).doesNotContain(INDEX_SENTINEL);
    }

    @Test
    @DisplayName("/api 未知路径不被回退：仍走契约 404 not_found，而非 200 index.html")
    void unknownApiPathStaysNotFound() {
        ResponseEntity<String> response = rest.getForEntity("/api/definitely-not-a-route", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).doesNotContain(INDEX_SENTINEL);
    }

    @Test
    @DisplayName("/actuator/health 不被回退遮蔽：仍返回 JSON 健康体")
    void actuatorPrefixNotShadowed() {
        ResponseEntity<String> response = rest.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("status");
        assertThat(response.getBody()).doesNotContain(INDEX_SENTINEL);
    }

    @Test
    @DisplayName("/internal/shutdown GET 不被回退：仍是 405/404 语义，非 200 index.html")
    void internalPrefixNotShadowed() {
        ResponseEntity<String> response = rest.exchange("/internal/shutdown",
                HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), String.class);

        // 守卫关闭时控制器返回 404；无论如何都不能被回退成 200 HTML
        assertThat(response.getStatusCode()).isNotEqualTo(HttpStatus.OK);
        assertThat(response.getBody() == null || !response.getBody().contains(INDEX_SENTINEL)).isTrue();
    }

    @Test
    @DisplayName("/ws/terminal GET 不被回退遮蔽：裸 HTTP 探测不是 index.html")
    void wsPrefixNotShadowed() {
        HttpHeaders headers = new HttpHeaders();
        headers.setOrigin("http://localhost:0");
        ResponseEntity<String> response = rest.exchange(WebSocketConfiguration.TERMINAL_ENDPOINT,
                HttpMethod.GET, new HttpEntity<>(headers), String.class);

        // WS 端点要么拒握手（4xx），但绝不会把升级请求当成 SPA 路由返回 200 HTML
        assertThat(response.getBody() == null || !response.getBody().contains(INDEX_SENTINEL))
                .as("WS 路径不得被 SPA 回退吞成 index.html").isTrue();
    }
}
