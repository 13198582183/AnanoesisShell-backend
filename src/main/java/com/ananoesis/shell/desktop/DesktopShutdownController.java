package com.ananoesis.shell.desktop;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内部停止端点 {@code POST /internal/shutdown}（design D4）。
 *
 * <p>三重约束的落点：仅 POST（其它方法 Spring 自动 405）、须持票（由
 * {@link DesktopGuardFilter} 对 {@code /internal/**} 无票 401 挡住）、
 * 仅回环（{@code server.address=127.0.0.1} 任务 2.5 保证）。命中后经协调器异步
 * 触发优雅关闭，先回 202 让壳确认请求已受理。</p>
 *
 * <p>守卫关闭（Web/dev 形态）时本端点返回 404：它不是桌面部署的一部分，
 * 也不该在无票保护时对任何本机进程可用。</p>
 *
 * <p>契约豁免：该端点不属于业务 API，不进 {@code contract/openapi.yaml}（见 design D4）。</p>
 */
@RestController
@RequestMapping("/internal")
public class DesktopShutdownController {

    private final DesktopGuard guard;
    private final DesktopShutdownCoordinator coordinator;

    public DesktopShutdownController(DesktopGuard guard, DesktopShutdownCoordinator coordinator) {
        this.guard = guard;
        this.coordinator = coordinator;
    }

    @PostMapping("/shutdown")
    public ResponseEntity<Void> shutdown() {
        if (!guard.enabled()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        coordinator.requestShutdown();
        return ResponseEntity.accepted().build();
    }
}
