package com.ananoesis.shell.desktop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 生产停止实现：独立线程触发 {@code context.close()}（design D4）。
 *
 * <p>WHY 异步：调用方（壳 {@code POST /internal/shutdown}）需要先拿到 202 再等进程退出。
 * 若在请求线程内同步 close，容器会在响应写回前销毁 servlet 栈，壳收到的是连接重置而非 202。
 * 新线程里 close 走的是 Spring Boot 既有 graceful 生命周期（在途 WS/审批收尾），
 * 正是 Windows 下 {@code Process.destroy()} 不触发的 shutdown hook 语义的落地入口。</p>
 */
public final class ContextCloseShutdownCoordinator implements DesktopShutdownCoordinator {

    private static final Logger LOG = LoggerFactory.getLogger(ContextCloseShutdownCoordinator.class);

    private final ConfigurableApplicationContext context;

    public ContextCloseShutdownCoordinator(ConfigurableApplicationContext context) {
        this.context = context;
    }

    @Override
    public void requestShutdown() {
        LOG.info("收到桌面停止请求，异步触发优雅关闭");
        Thread shutdownThread = new Thread(context::close, "desktop-shutdown");
        shutdownThread.setDaemon(false);
        shutdownThread.start();
    }
}
