-- ============================================================================
-- V2：多连接实例、运行账本、SFTP 传输（design.md D10）
-- 依据：openspec/changes/add-multi-session-agent-terminal/design.md D10
--
-- 全局约定延续 V1：
-- 1. 主键 TEXT（UUID）
-- 2. 时间 TEXT（ISO-8601）
-- 3. 布尔 INTEGER 0/1 + CHECK
-- 4. 本迁移 MUST NOT 写入任何凭据或秘密
-- 5. 不使用 SQLite JSON 扩展迁移模型配置（JSON 仅作为 TEXT 快照存储）
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. ai_conversations 新增 session_id 字段（可空，引用 sessions）
-- WHY ON DELETE SET NULL：删除会话不应销毁对话审计记录，只解除关联
-- ---------------------------------------------------------------------------
ALTER TABLE ai_conversations ADD COLUMN session_id TEXT REFERENCES sessions(id) ON DELETE SET NULL;

-- 覆盖按会话+时间分页查询场景
CREATE INDEX ix_ai_conversations_session ON ai_conversations(session_id, created_at, id);

-- ---------------------------------------------------------------------------
-- 2. ai_messages 新增字段
-- WHY source 区分 ai / shell_event：shell 事件（如连接状态变化）需与 AI 消息在审计上可区分
-- WHY command_id / run_id 可空：只有工具调用类消息才关联到具体执行
-- ---------------------------------------------------------------------------
ALTER TABLE ai_messages ADD COLUMN source TEXT DEFAULT 'ai' CHECK (source IN ('ai', 'shell_event'));
ALTER TABLE ai_messages ADD COLUMN command_id TEXT;
ALTER TABLE ai_messages ADD COLUMN run_id TEXT;

-- ---------------------------------------------------------------------------
-- 3. ai_runs 表 - AI 运行记录（design.md D10「运行账本」）
-- WHY status CHECK 含 unknown：远端状态不确定时不应阻止记录落库
-- WHY model_config_snapshot 为 TEXT：JSON 快照仅作文本存储，不依赖 SQLite JSON 扩展
-- WHY 唯一索引 ux_ai_runs_session_active：同一会话同时只能有一条 running 记录
-- ---------------------------------------------------------------------------
CREATE TABLE ai_runs (
    id TEXT PRIMARY KEY,
    session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    conversation_id TEXT REFERENCES ai_conversations(id) ON DELETE SET NULL,
    status TEXT NOT NULL CHECK (status IN ('running', 'completed', 'failed', 'stopped', 'unknown')),
    model_config_snapshot TEXT,
    context_recovery_count INTEGER NOT NULL DEFAULT 0,
    cancellation_generation INTEGER NOT NULL DEFAULT 0,
    started_at TEXT NOT NULL,
    ended_at TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

-- 同一会话同时只允许一条 running 状态
CREATE UNIQUE INDEX ux_ai_runs_session_active ON ai_runs(session_id) WHERE status = 'running';

-- ---------------------------------------------------------------------------
-- 4. command_executions 表 - 命令执行账本（design.md D10「命令目标快照」）
-- WHY source 区分 agent_tool / manual：审计必须能分辨 AI 发起与人手动执行
-- WHY claim_status 含 unknown：远端状态不确定时不应阻止记录落库
-- WHY 唯一索引 (run_id, call_id) WHERE source='agent_tool'：agent 工具调用必须幂等可追溯
-- ---------------------------------------------------------------------------
CREATE TABLE command_executions (
    id TEXT PRIMARY KEY,
    source TEXT NOT NULL CHECK (source IN ('agent_tool', 'manual')),
    run_id TEXT REFERENCES ai_runs(id) ON DELETE SET NULL,
    call_id TEXT,
    session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    conversation_id TEXT REFERENCES ai_conversations(id) ON DELETE SET NULL,
    command TEXT NOT NULL,
    claim_status TEXT NOT NULL CHECK (claim_status IN ('claimed', 'sent', 'completed', 'unknown')),
    cwd TEXT,
    exit_code INTEGER,
    output_truncated INTEGER NOT NULL DEFAULT 0 CHECK (output_truncated IN (0, 1)),
    stdout TEXT,
    stderr TEXT,
    claimed_at TEXT,
    sent_at TEXT,
    completed_at TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

-- agent 工具调用幂等：(run_id, call_id) 唯一
CREATE UNIQUE INDEX ux_command_executions_run_call ON command_executions(run_id, call_id) WHERE source = 'agent_tool';

-- ---------------------------------------------------------------------------
-- 5. approvals 新增版本字段（design.md D10「审批版本」）
-- WHY run_id / call_id：审批与具体运行/调用关联
-- WHY version / expected_version：乐观并发控制，防止并发修改审批
-- WHY final_command：审批通过后可能被修改的最终命令
-- WHY target_session_id：审批目标会话，可与执行会话不同
-- ---------------------------------------------------------------------------
ALTER TABLE approvals ADD COLUMN run_id TEXT REFERENCES ai_runs(id) ON DELETE SET NULL;
ALTER TABLE approvals ADD COLUMN call_id TEXT;
ALTER TABLE approvals ADD COLUMN version INTEGER NOT NULL DEFAULT 1;
ALTER TABLE approvals ADD COLUMN expected_version INTEGER NOT NULL DEFAULT 1;
ALTER TABLE approvals ADD COLUMN final_command TEXT;
ALTER TABLE approvals ADD COLUMN target_session_id TEXT REFERENCES sessions(id) ON DELETE SET NULL;

-- ---------------------------------------------------------------------------
-- 6. file_transfers 表 - 文件传输（design.md D10「SFTP 传输」）
-- WHY direction CHECK upload / download：传输方向是审计要素
-- WHY status 含 unknown：远端状态不确定时不应阻止记录落库
-- WHY expected_target 为 TEXT：JSON 格式 {size, mtime, mode}，不依赖 SQLite JSON 扩展
-- ---------------------------------------------------------------------------
CREATE TABLE file_transfers (
    id TEXT PRIMARY KEY,
    session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    direction TEXT NOT NULL CHECK (direction IN ('upload', 'download')),
    remote_path TEXT NOT NULL,
    file_name TEXT NOT NULL,
    declared_size INTEGER NOT NULL,
    transferred_bytes INTEGER NOT NULL DEFAULT 0,
    status TEXT NOT NULL CHECK (status IN ('queued', 'ready', 'transferring', 'publishing', 'published', 'delivered', 'failed', 'cancelled', 'expired', 'unknown')),
    overwrite INTEGER NOT NULL DEFAULT 0 CHECK (overwrite IN (0, 1)),
    expected_target TEXT,
    temp_file_path TEXT,
    failure_code TEXT,
    download_ticket_hash TEXT,
    download_ticket_expires_at TEXT,
    queued_at TEXT NOT NULL,
    ready_at TEXT,
    ready_deadline TEXT,
    transfer_started_at TEXT,
    transfer_completed_at TEXT,
    published_at TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

-- 按会话+状态查询传输列表
CREATE INDEX ix_file_transfers_session_status ON file_transfers(session_id, status);
