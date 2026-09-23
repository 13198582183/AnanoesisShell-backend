package com.ananoesis.shell;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * AnanoesisShell 后端启动入口。
 *
 * <p>职责边界（design.md 架构总览）：本进程承载 SSH 双通道服务、AI 智能体与审批闸门、
 * 凭据安全存储与 SQLite 持久化；前端为独立的 Vue3 应用，经 REST + WebSocket 交互。</p>
 */
@SpringBootApplication
public class AnanoesisShellApplication {

    public static void main(String[] args) {
        SpringApplication.run(AnanoesisShellApplication.class, args);
    }
}
