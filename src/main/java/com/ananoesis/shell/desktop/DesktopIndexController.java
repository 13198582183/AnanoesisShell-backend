package com.ananoesis.shell.desktop;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 桌面形态根路径转发（任务 3.1，design D2）。
 *
 * <p>WHY 需要这个控制器：GET {@code /} 在 {@code /**} 资源处理器里解析出的
 * within-mapping 路径是空串，Spring 6.2 的
 * {@code ResourceHttpRequestHandler#getResource} 对空路径在
 * {@code ResourceHandlerUtils.shouldIgnoreInputPath} 处直接返回 null，
 * <b>resolver 链根本不会被询问</b>——SPA 回退解析器对根路径天然失效，
 * 只能在这里显式 forward 到 {@code /index.html}，让它以正常资源路径的身份
 * 走一遍静态解析（实证版本：spring-webmvc 6.2.19）。</p>
 *
 * <p>forward 是容器内部派发，不会再过一遍只注册 REQUEST dispatch 的桌面守卫
 * 过滤器；持票校验已在最外层请求上完成，语义正确。</p>
 */
@Controller
@Profile("desktop")
public class DesktopIndexController {

    @GetMapping("/")
    public String forwardRootToIndex() {
        return "forward:/index.html";
    }
}
