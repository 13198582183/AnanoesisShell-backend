package com.ananoesis.shell.desktop;

/**
 * 桌面停止触发器（design D4）。
 *
 * <p>抽这一层只为可测性：控制器把"是否停止"的决策交给它，测试注入一个记录型
 * 假实现即可断言"命中即触发"，而无需真的关闭测试上下文（那会杀死整个测试 JVM）。</p>
 */
@FunctionalInterface
public interface DesktopShutdownCoordinator {

    /** 请求停止后端。生产实现异步触发 {@code context.close()} 走 graceful。 */
    void requestShutdown();
}
