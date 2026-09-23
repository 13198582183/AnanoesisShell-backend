-- ============================================================================
-- V1：AnanoesisShell MVP 初始数据模型（7 张业务表）
-- 依据：openspec/changes/add-ssh-ai-agent-mvp/specs/
--         ssh-connection / credential-store / model-provider / ai-agent / command-approval
--       以及 design.md D6（SQLite+WAL+MyBatis-Plus）、D7（REST/WebSocket 契约）
--
-- 全局约定（WHY）：
-- 1. 主键统一 TEXT（应用侧生成的 32 位 UUID）。
--    SQLite 的 INTEGER PRIMARY KEY 依赖自增回写，跨驱动取 generated key 行为不稳定；
--    UUID 也保证未来多机数据导入/合并时不会主键冲突。
-- 2. 时间列统一 TEXT（ISO-8601，如 2026-09-21T10:30:45）。
--    SQLite 没有 datetime 存储类；TEXT 亲和性下 sqlite-jdbc 会把 LocalDateTime 写成
--    ISO-8601 字符串并可无损读回，同时便于用任意 SQLite 客户端人工排查。
-- 3. 布尔列用 INTEGER 0/1 + CHECK 约束（SQLite 无 BOOLEAN 类型）。
-- 4. 安全底线（credential-store spec「数据库中 MUST NOT 存储明文凭据」）：
--    全库唯一的凭据落点是 credentials.ciphertext；任何表都不得出现明文
--    密码 / 私钥 / passphrase / api key 列。
-- 5. 本迁移 MUST NOT 写入任何 api key 或默认凭据（credential-store spec「API Key 不硬编码」）。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- hosts：服务器连接配置（ssh-connection spec「服务器配置管理」）
-- 必填：主机地址、端口、登录用户名、认证方式；可选：分组、备注。
-- WHY 不在此表存任何凭据：认证材料一律外移到 credentials 表以密文保存，
--     使 hosts 表可以安全地被导出/日志化/前端直读。
-- ---------------------------------------------------------------------------
CREATE TABLE hosts (
    id          TEXT    PRIMARY KEY,
    name        TEXT    NOT NULL,
    host        TEXT    NOT NULL,
    port        INTEGER NOT NULL DEFAULT 22,
    username    TEXT    NOT NULL,
    auth_type   TEXT    NOT NULL CHECK (auth_type IN ('password', 'private_key')),
    group_name  TEXT,
    remark      TEXT,
    created_at  TEXT    NOT NULL,
    updated_at  TEXT    NOT NULL
);

CREATE INDEX ix_hosts_host ON hosts (host, port);
CREATE INDEX ix_hosts_group ON hosts (group_name);

-- ---------------------------------------------------------------------------
-- credentials：全库唯一的密文存储表（credential-store spec）
-- 承载 SSH 密码 / 私钥 / 私钥 passphrase / 大模型 api key。
-- WHY 用 (owner_type, owner_id, credential_type) 三元组而非固定外键：
--     凭据的宿主既可能是主机（host），也可能是模型配置（model_config）或设置项（setting），
--     泛化宿主让新增凭据种类无需改表结构。宿主行删除时的清理在服务层显式处理。
-- ciphertext 为 AES-GCM 密文信封（Base64），信封内自带 IV 与主密钥来源标识，
--     因此本表无需（也不应）再存任何密钥材料。
-- ---------------------------------------------------------------------------
CREATE TABLE credentials (
    id              TEXT PRIMARY KEY,
    owner_type      TEXT NOT NULL CHECK (owner_type IN ('host', 'model_config', 'setting')),
    owner_id        TEXT NOT NULL,
    credential_type TEXT NOT NULL CHECK (credential_type IN
                        ('ssh_password', 'ssh_private_key', 'ssh_passphrase', 'llm_api_key', 'generic_secret')),
    ciphertext      TEXT NOT NULL,
    created_at      TEXT NOT NULL,
    updated_at      TEXT NOT NULL
);

CREATE UNIQUE INDEX ux_credentials_owner ON credentials (owner_type, owner_id, credential_type);

-- ---------------------------------------------------------------------------
-- sessions：SSH 连接会话生命周期（ssh-connection spec「连接会话生命周期」）
-- WHY 记录 session_type：design.md D4 规定交互式 PTY 与 AI exec 是两条独立通道，
--     审计与排障需要能区分"人用的终端"与"AI 跑命令的通道"。
-- close_reason 覆盖 spec 要求的三种终止情形：主动断开 / 超时 / 远端关闭（另含错误）。
-- ---------------------------------------------------------------------------
CREATE TABLE sessions (
    id            TEXT    PRIMARY KEY,
    host_id       TEXT    NOT NULL REFERENCES hosts (id) ON DELETE CASCADE,
    session_type  TEXT    NOT NULL CHECK (session_type IN ('interactive_pty', 'exec')),
    status        TEXT    NOT NULL CHECK (status IN ('connecting', 'open', 'closed', 'error')),
    started_at    TEXT    NOT NULL,
    ended_at      TEXT,
    close_reason  TEXT    CHECK (close_reason IN
                    ('user_disconnect', 'remote_closed', 'timeout', 'auth_failed', 'unreachable', 'error')),
    error_message TEXT,
    created_at    TEXT    NOT NULL,
    updated_at    TEXT    NOT NULL
);

CREATE INDEX ix_sessions_host ON sessions (host_id, started_at);
CREATE INDEX ix_sessions_status ON sessions (status);

-- ---------------------------------------------------------------------------
-- ai_conversations：AI 会话（ai-agent spec「多轮上下文延续」）
-- host_id 可空且 ON DELETE SET NULL：删掉服务器配置不应连带销毁历史对话审计。
-- ---------------------------------------------------------------------------
CREATE TABLE ai_conversations (
    id          TEXT    PRIMARY KEY,
    host_id     TEXT    REFERENCES hosts (id) ON DELETE SET NULL,
    title       TEXT,
    status      TEXT    NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'archived')),
    created_at  TEXT    NOT NULL,
    updated_at  TEXT    NOT NULL
);

CREATE INDEX ix_ai_conversations_host ON ai_conversations (host_id, created_at);

-- ---------------------------------------------------------------------------
-- ai_messages：会话消息与工具调用明细
-- WHY 把 reasoning_content 单独成列（model-provider spec「思考与非思考双模式」）：
--     思考过程需在界面上与最终回答分区展示，混在 content 里将不可区分。
-- WHY 把 tool_name / tool_arguments / tool_result 单独成列（ai-agent spec「操作透明性」）：
--     每一次工具调用的名称、参数与结果（或"用户已拒绝"）都必须可追溯、可查询，
--     塞进 JSON 大字段会让审计检索退化为全表扫描。
-- ---------------------------------------------------------------------------
CREATE TABLE ai_messages (
    id                TEXT    PRIMARY KEY,
    conversation_id   TEXT    NOT NULL REFERENCES ai_conversations (id) ON DELETE CASCADE,
    seq               INTEGER NOT NULL,
    role              TEXT    NOT NULL CHECK (role IN ('system', 'user', 'assistant', 'tool')),
    content           TEXT,
    reasoning_content TEXT,
    tool_calls        TEXT,
    tool_call_id      TEXT,
    tool_name         TEXT,
    tool_arguments    TEXT,
    tool_result       TEXT,
    tool_rejected     INTEGER NOT NULL DEFAULT 0 CHECK (tool_rejected IN (0, 1)),
    created_at        TEXT    NOT NULL
);

-- 同一会话内 seq 唯一，保证多轮上下文可按序重建
CREATE UNIQUE INDEX ux_ai_messages_seq ON ai_messages (conversation_id, seq);

-- ---------------------------------------------------------------------------
-- approvals：人工审批闸门与审计日志（command-approval spec「审批审计日志」）
-- spec 要求每条记录至少含：时间、目标服务器、工具名与参数、AI 分析、用户决定、执行结果。
-- WHY decision 与 execution_status 分开：
--     "用户批准"与"命令是否真的跑成功"是两件事——批准后仍可能超时或输出被截断，
--     审计必须能同时还原人的决定与机器的执行结果。
-- WHY decided_by 区分 user / system：超时兜底由系统按"取消"处理（spec「审批超时自动取消」），
--     必须与用户主动点击"取消"在审计上可区分。
-- 安全约束：本表所有文本列 MUST NOT 含明文凭据；命令文本由服务层负责脱敏后再落库。
-- ---------------------------------------------------------------------------
CREATE TABLE approvals (
    id               TEXT    PRIMARY KEY,
    conversation_id  TEXT    REFERENCES ai_conversations (id) ON DELETE SET NULL,
    message_id       TEXT    REFERENCES ai_messages (id) ON DELETE SET NULL,
    session_id       TEXT    REFERENCES sessions (id) ON DELETE SET NULL,
    host_id          TEXT    REFERENCES hosts (id) ON DELETE SET NULL,
    tool_name        TEXT    NOT NULL,
    tool_arguments   TEXT,
    ai_analysis      TEXT,
    decision         TEXT    NOT NULL DEFAULT 'pending'
                     CHECK (decision IN ('pending', 'approved', 'rejected', 'timeout')),
    decided_by       TEXT    CHECK (decided_by IN ('user', 'system')),
    requested_at     TEXT    NOT NULL,
    expires_at       TEXT,
    decided_at       TEXT,
    execution_status TEXT    CHECK (execution_status IN
                        ('not_executed', 'running', 'success', 'failed', 'timeout', 'truncated')),
    execution_result TEXT,
    exit_code        INTEGER,
    output_truncated INTEGER NOT NULL DEFAULT 0 CHECK (output_truncated IN (0, 1)),
    created_at       TEXT    NOT NULL,
    updated_at       TEXT    NOT NULL
);

CREATE INDEX ix_approvals_requested_at ON approvals (requested_at);
CREATE INDEX ix_approvals_host ON approvals (host_id, requested_at);
CREATE INDEX ix_approvals_decision ON approvals (decision);

-- ---------------------------------------------------------------------------
-- settings：应用设置项（键值）
-- WHY 本表只存非敏感配置：credential-store spec 规定 api key 等敏感项必须以密文存储，
--     统一落到 credentials 表；settings 里只保留可公开查看的行为开关与阈值，
--     从表结构层面杜绝"把 api key 顺手塞进 settings"的漂移。
-- 模型配置（provider / base_url / model）以 value_type='json' 存于此表，
--     其 api key 通过 credentials(owner_type='model_config') 单独密文保存。
-- ---------------------------------------------------------------------------
CREATE TABLE settings (
    setting_key   TEXT    PRIMARY KEY,
    setting_value TEXT,
    value_type    TEXT    NOT NULL DEFAULT 'string'
                  CHECK (value_type IN ('string', 'number', 'boolean', 'json')),
    description   TEXT,
    updated_at    TEXT    NOT NULL
);

-- 默认阈值：全部可追溯到具体 spec 场景，且均可被用户在设置界面覆盖。
-- 这些值 MUST NOT 包含任何凭据。
INSERT INTO settings (setting_key, setting_value, value_type, description, updated_at) VALUES
    ('ssh.connect.timeout.seconds', '15', 'number',
     'SSH 连接超时；超时后报告「连接失败：主机不可达」（ssh-connection spec）', '1970-01-01T00:00:00'),
    ('approval.timeout.seconds', '120', 'number',
     '审批等待时限；超时自动按「取消」处理并释放挂起资源（command-approval spec）', '1970-01-01T00:00:00'),
    ('run_command.timeout.seconds', '60', 'number',
     '经审批执行的命令超时；超时中断并提示「执行超时」（command-approval spec）', '1970-01-01T00:00:00'),
    ('run_command.max_output_bytes', '65536', 'number',
     '命令输出长度上限；超限截断并标注「输出已截断」（command-approval spec）', '1970-01-01T00:00:00'),
    ('read_file.max_lines', '500', 'number',
     'read_file 工具单次读取行数上限，防止超大文件撑爆上下文（ai-agent spec）', '1970-01-01T00:00:00'),
    ('model.default_thinking_mode', 'false', 'boolean',
     '默认思考/非思考模式（model-provider spec）', '1970-01-01T00:00:00');
