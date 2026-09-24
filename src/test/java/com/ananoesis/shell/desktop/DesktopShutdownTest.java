package com.ananoesis.shell.desktop;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 内部停止端点与协调器测试（任务 2.3）。
 *
 * <p>WHY standalone MockMvc 而非全量 {@code @SpringBootTest}：这里要钉住的是控制器的
 * 映射语义（仅 POST、守卫关时 404、命中即触发）与协调器的异步关闭，都不需要真实容器；
 * 独立轻量上下文避免真杀测试 JVM（design D4 的核心风险）。"无票 401"由过滤器负责，
 * 已在 {@code DesktopGuardIntegrationTest} 的守卫开启上下文中覆盖。</p>
 */
class DesktopShutdownTest {

    private MockMvc mockMvc(DesktopGuard guard, DesktopShutdownCoordinator coordinator) {
        return MockMvcBuilders.standaloneSetup(new DesktopShutdownController(guard, coordinator)).build();
    }

    @Test
    @DisplayName("守卫开启：POST /internal/shutdown → 202 且触发协调器")
    void postShutdownTriggersCoordinator() throws Exception {
        AtomicBoolean triggered = new AtomicBoolean();
        mockMvc(new DesktopGuard(true, "tok"), () -> triggered.set(true))
                .perform(post("/internal/shutdown"))
                .andExpect(status().isAccepted());
        assertThat(triggered).as("命中即触发优雅关闭").isTrue();
    }

    @Test
    @DisplayName("非 POST 方法 → 405（端点仅受理 POST）")
    void nonPostIsMethodNotAllowed() throws Exception {
        mockMvc(new DesktopGuard(true, "tok"), () -> {
            throw new AssertionError("GET 不得触发关闭");
        }).perform(get("/internal/shutdown")).andExpect(status().isMethodNotAllowed());
    }

    @Test
    @DisplayName("守卫关闭（Web/dev）：端点视为不存在 → 404，不触发关闭")
    void shutdownUnknownWhenGuardDisabled() throws Exception {
        AtomicBoolean triggered = new AtomicBoolean();
        mockMvc(new DesktopGuard(false, null), () -> triggered.set(true))
                .perform(post("/internal/shutdown"))
                .andExpect(status().isNotFound());
        assertThat(triggered).as("非桌面部署不得经此端点关停").isFalse();
    }

    @Test
    @DisplayName("协调器：requestShutdown 在独立线程触发 context.close()")
    void coordinatorClosesContextAsynchronously() throws Exception {
        ConfigurableApplicationContext context = mock(ConfigurableApplicationContext.class);
        CountDownLatch closed = new CountDownLatch(1);
        doAnswer(invocation -> {
            closed.countDown();
            return null;
        }).when(context).close();

        new ContextCloseShutdownCoordinator(context).requestShutdown();

        assertThat(closed.await(5, TimeUnit.SECONDS))
                .as("requestShutdown 应异步关闭上下文（走既有 graceful）").isTrue();
    }
}
