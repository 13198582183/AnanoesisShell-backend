# AnanoesisShell 后端

AI 驱动远程终端（AnanoesisShell）的后端服务：承载 SSH 连接运行时（持久 PTY / exec）、AI 智能体回合（流式 + 工具循环 + 审批闸门）、会话/账本/传输持久化与 REST + WebSocket 接口。

> 真相源：接口契约在 `../contract/openapi.yaml`（REST）与 `../contract/asyncapi.yaml`（WS）；行为规格在 `../openspec/`。工程规范见主仓库 `.qoder/rules/backend-standards.md`、`database-standards.md`。

## 技术栈

| 项 | 选型 |
|---|---|
| 语言 / 框架 | Java 17 · Spring Boot 3.5.x（web / websocket / validation / actuator） |
| AI 层 | Spring AI 1.1.x（OpenAI 兼容 provider，工具由应用侧路由，`internalToolExecutionEnabled=false`） |
| SSH | SSHD（PTY 交互 + exec 隔离）+ Shell 集成 OSC 钩子 |
| 持久化 | SQLite（WAL）+ MyBatis-Plus + Flyway（`src/main/resources/db/migration`） |
| 凭据 | OS 密钥库主密钥 + AES-256-GCM 信封加密 |
| 契约代码 | openapi-generator（`interfaceOnly`，生成物在 `target/generated-sources/openapi`，不手改） |

## 目录地图（`src/main/java/com/ananoesis/shell/`）

- `controller/` — OpenAPI 生成接口实现 + 统一异常处理
- `service/` — 业务规则与事务（主机/会话/模型配置/审批/账本等）
- `ssh/` — SSH 连接运行时：PTY 网关、命令调度（`PtyCommandScheduler`）、Shell 集成安装、SFTP
- `ai/` — 智能体回合（`AiAgentService`）、工具集、系统提示词、上下文超限恢复
- `ws/` — WebSocket 处理器：`/ws/terminal`、`/ws/ai`（含 `stop_turn` 停止上行）、`/ws/approval`
- `security/` — 凭据加密与密钥库；`entity/` `mapper/` — 持久层；`config/` `support/` — 装配与公共入口

## 运行与测试

```powershell
# 启动（默认端口 18080，数据落 ~/.ananoesis/data.db）
.\mvnw.cmd spring-boot:run

# 全量测试（提交前必须全绿；测试使用临时 SQLite 与内嵌 SSHD，不碰真实数据）
.\mvnw.cmd test

# 定向测试
.\mvnw.cmd test "-Dtest=AiAgentServiceTest"
```

WS 代理与 REST 前缀由前端 `vite.config.ts` 开发代理对齐，本地联调需前端 dev server（3000）同时运行。
