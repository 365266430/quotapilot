-- QuotaPilot 生产参考 DDL（PostgreSQL）
-- 金额单位全项目统一：千分之一分（1e-5 元），BIGINT 存储，禁止 double。
-- 应用默认 ddl-auto=update 可自动建表；生产建议以本脚本 + ddl-auto=validate 管理。

CREATE TABLE IF NOT EXISTS accounts (
    account_id        VARCHAR(64) PRIMARY KEY,
    scope_type        VARCHAR(20)  NOT NULL,
    scope_id          VARCHAR(128) NOT NULL,
    quota_limit_minor BIGINT       NOT NULL,
    currency          VARCHAR(8)   NOT NULL,
    status            VARCHAR(16)  NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uk_accounts_scope UNIQUE (scope_type, scope_id)
);

CREATE TABLE IF NOT EXISTS ledger_entries (
    entry_id        VARCHAR(64) PRIMARY KEY,
    account_id      VARCHAR(64) NOT NULL,
    request_id      VARCHAR(64),
    type            VARCHAR(16)  NOT NULL,   -- HOLD/SETTLE/RELEASE/ADJUST
    amount_minor    BIGINT       NOT NULL,
    kind            VARCHAR(16)  NOT NULL,   -- ESTIMATE/ACTUAL/ADJUSTMENT（P1）
    price_version_id VARCHAR(64),
    reason          VARCHAR(64),
    evidence_ref    VARCHAR(128),
    idempotency_key VARCHAR(128) UNIQUE,     -- ADJUST 防重入账（P6）
    trace_id        VARCHAR(64),
    created_at      TIMESTAMPTZ  NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_ledger_account ON ledger_entries (account_id, created_at);
CREATE INDEX IF NOT EXISTS ix_ledger_request ON ledger_entries (request_id);

CREATE TABLE IF NOT EXISTS reservations (
    hold_id             VARCHAR(64) PRIMARY KEY,
    request_id          VARCHAR(64) UNIQUE NOT NULL,  -- 预留幂等根
    account_id          VARCHAR(64) NOT NULL,
    reserved_amount_minor BIGINT    NOT NULL,
    price_version_id    VARCHAR(64) NOT NULL,         -- P5 价格快照固化
    trace_id            VARCHAR(64),
    created_at          TIMESTAMPTZ NOT NULL,
    expires_at          TIMESTAMPTZ,
    status              VARCHAR(16) NOT NULL,         -- RESERVED/SETTLED/RELEASED
    finished_at         TIMESTAMPTZ,
    finish_reason       VARCHAR(32),
    version             BIGINT       NOT NULL DEFAULT 0  -- 乐观锁（状态机并发保护）
);

CREATE TABLE IF NOT EXISTS settlement_records (
    request_id         VARCHAR(64) PRIMARY KEY,
    hold_id            VARCHAR(64) NOT NULL,
    account_id         VARCHAR(64) NOT NULL,
    actual_amount_minor  BIGINT    NOT NULL,
    charged_amount_minor BIGINT    NOT NULL,
    refund_amount_minor  BIGINT    NOT NULL,
    status             VARCHAR(16) NOT NULL,
    price_version_id   VARCHAR(64),
    trace_id           VARCHAR(64),
    created_at         TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS exposures (
    exposure_id          VARCHAR(64) PRIMARY KEY,
    request_id           VARCHAR(64) UNIQUE NOT NULL,
    hold_id              VARCHAR(64) NOT NULL,
    account_id           VARCHAR(64) NOT NULL,
    estimated_amount_minor BIGINT    NOT NULL,
    reason               VARCHAR(20) NOT NULL,   -- TIMEOUT/DISCONNECT/SUPPLIER_UNKNOWN
    state                VARCHAR(30) NOT NULL,   -- PENDING/SETTLED/CLOSED_WITH_ADJUSTMENT
    created_at           TIMESTAMPTZ NOT NULL,
    grace_deadline       TIMESTAMPTZ,
    resolved_at          TIMESTAMPTZ,
    resolved_amount_minor  BIGINT,
    evidence_ref         VARCHAR(128),
    version              BIGINT      NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS ix_expo_state ON exposures (state, grace_deadline);

CREATE TABLE IF NOT EXISTS price_versions (
    price_version_id   VARCHAR(64) PRIMARY KEY,
    sku_key            VARCHAR(128) NOT NULL,
    unit               VARCHAR(16)  NOT NULL,
    price_per_unit_minor BIGINT     NOT NULL,
    currency           VARCHAR(8)   NOT NULL,
    version            BIGINT       NOT NULL,
    effective_from     TIMESTAMPTZ  NOT NULL,
    effective_to       TIMESTAMPTZ,              -- NULL = 长期有效；旧版本不删除（P5）
    status             VARCHAR(16)  NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_price_sku ON price_versions (sku_key);

CREATE TABLE IF NOT EXISTS quota_rules (
    rule_id             VARCHAR(64) PRIMARY KEY,
    scope_type          VARCHAR(20)  NOT NULL,
    scope_id            VARCHAR(128) NOT NULL,
    model               VARCHAR(128),
    quota_limit_minor   BIGINT       NOT NULL,
    currency            VARCHAR(8)   NOT NULL,
    shared_among_members BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMPTZ  NOT NULL
);

CREATE TABLE IF NOT EXISTS quota_rule_audit (
    id         BIGSERIAL PRIMARY KEY,
    rule_id    VARCHAR(64) NOT NULL,
    operator   VARCHAR(64),
    action     VARCHAR(16) NOT NULL,
    detail     VARCHAR(512),
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS team_members (
    id      BIGSERIAL PRIMARY KEY,
    team_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    CONSTRAINT uk_team_user UNIQUE (team_id, user_id)
);

CREATE TABLE IF NOT EXISTS usage_events (
    usage_event_id     VARCHAR(64) PRIMARY KEY,
    request_id         VARCHAR(64) NOT NULL,
    supplier_request_id VARCHAR(64),
    account_id         VARCHAR(64),
    sku                VARCHAR(128),
    usage_type         VARCHAR(16),
    quantity           BIGINT      NOT NULL,
    occurred_at        TIMESTAMPTZ NOT NULL,
    source             VARCHAR(20) NOT NULL,   -- MOCK/OPENAI/INTERNAL/SUPPLIER_CALLBACK
    seq                BIGINT      NOT NULL,
    trace_id           VARCHAR(64),
    CONSTRAINT uk_usage_idem UNIQUE (request_id, source, seq)  -- P6/Q3 幂等
);
CREATE INDEX IF NOT EXISTS ix_usage_account ON usage_events (account_id, occurred_at);

CREATE TABLE IF NOT EXISTS idempotency (
    idem_key   VARCHAR(128) PRIMARY KEY,
    result     VARCHAR(512) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL
);

CREATE TABLE IF NOT EXISTS outbox (
    id             BIGSERIAL PRIMARY KEY,
    aggregate_type VARCHAR(32)  NOT NULL,
    aggregate_id   VARCHAR(64)  NOT NULL,
    event_type     VARCHAR(64)  NOT NULL,
    payload_json   VARCHAR(4096),
    status         VARCHAR(16)  NOT NULL,   -- PENDING/SENT/FAILED
    attempts       INT          NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ  NOT NULL,
    dispatched_at  TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS ix_outbox_status ON outbox (status, created_at);

CREATE TABLE IF NOT EXISTS supplier_charges (
    supplier_request_id VARCHAR(64) PRIMARY KEY,
    request_id          VARCHAR(64) NOT NULL,
    account_id          VARCHAR(64),
    model               VARCHAR(128),
    units               BIGINT      NOT NULL,
    amount_minor        BIGINT      NOT NULL,
    seq                 BIGINT      NOT NULL,
    billed_at           TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_charge_seq UNIQUE (request_id, seq)
);

CREATE TABLE IF NOT EXISTS alerts (
    id         BIGSERIAL PRIMARY KEY,
    type       VARCHAR(32)  NOT NULL,
    severity   VARCHAR(8)   NOT NULL,
    account_id VARCHAR(64),
    request_id VARCHAR(64),
    message    VARCHAR(1024),
    created_at TIMESTAMPTZ  NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_alerts_time ON alerts (created_at);

CREATE TABLE IF NOT EXISTS rate_limit_rules (
    account_id               VARCHAR(64) PRIMARY KEY,
    requests_per_second      BIGINT NOT NULL DEFAULT 0,
    tokens_per_minute        BIGINT NOT NULL DEFAULT 0,
    billing_units_per_minute BIGINT NOT NULL DEFAULT 0
);
