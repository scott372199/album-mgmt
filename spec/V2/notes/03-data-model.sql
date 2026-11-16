-- =============================================================================
-- album-console · V1__core.sql
-- 基础设施管理平台 · PostgreSQL 16 · Flyway 基线脚本
--
-- 约定 / Conventions
--   1. 事实表只追加（append-only）。修正靠新行 + superseded_by，禁止 UPDATE 覆盖语义字段。
--   2. 派生表（*_derived / allocation / kpi_snapshot）必须可从事实表全量重建。
--   3. 所有金额 NUMERIC(20,6)，货币单独一列；金额可为 NULL 表示"未知"，禁止用 0 代替。
--   4. 所有时间 TIMESTAMPTZ，入库前统一 UTC。
--   5. 枚举用 CHECK 约束而非 PG ENUM 类型——枚举扩展不锁表。
--   6. LTREE 标签以字母开头：数字开头的目录名（01-storage）规范化为 l_01_storage。
--
-- 版本 / Version: 1.0   状态 / Status: 首次发布后不再修改，变更走 V2__*.sql
-- =============================================================================

CREATE EXTENSION IF NOT EXISTS ltree;
CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- =============================================================================
-- 0. 平台自身 / Platform
-- =============================================================================

CREATE TABLE app_user (
    id              BIGSERIAL PRIMARY KEY,
    username        TEXT        NOT NULL UNIQUE,
    display_name    TEXT        NOT NULL,
    password_hash   TEXT        NOT NULL,             -- bcrypt / argon2id，绝不明文
    role            TEXT        NOT NULL DEFAULT 'VIEWER',
    active          BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_login_at   TIMESTAMPTZ,
    CONSTRAINT ck_user_role CHECK (role IN ('OWNER', 'VIEWER'))
);
COMMENT ON TABLE app_user IS '平台账号。单租户，不做多租户隔离。';

CREATE TABLE auth_session (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         BIGINT      NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    issued_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ NOT NULL,
    -- 二次确认窗口：敏感操作（dispatch / export / config）在此时间前免重验
    elevated_until  TIMESTAMPTZ,
    ip              TEXT,
    user_agent      TEXT,
    revoked_at      TIMESTAMPTZ,
    CONSTRAINT ck_session_window CHECK (expires_at > issued_at)
);
CREATE INDEX ix_auth_session_user ON auth_session (user_id) WHERE revoked_at IS NULL;

CREATE TABLE audit_log (
    id              BIGSERIAL PRIMARY KEY,
    occurred_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    actor_id        BIGINT      REFERENCES app_user(id),
    actor_label     TEXT        NOT NULL,             -- 冗余快照，用户改名/删除后仍可读
    action          TEXT        NOT NULL,             -- dispatch_apply / export_cost / waive_finding / ...
    target_kind     TEXT,
    target_ref      TEXT,
    result          TEXT        NOT NULL,
    detail          JSONB       NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT ck_audit_result CHECK (result IN ('OK', 'DENIED', 'FAILED'))
);
CREATE INDEX ix_audit_time  ON audit_log (occurred_at DESC);
CREATE INDEX ix_audit_actor ON audit_log (actor_id, occurred_at DESC);
COMMENT ON TABLE audit_log IS '状态变更操作留痕，保留 12 个月（R-PLT-04）。';

CREATE TABLE config_version (
    id              BIGSERIAL PRIMARY KEY,
    scope           TEXT        NOT NULL,             -- 'drift' / 'finops' / 'notify' / 'slo'
    version         INT         NOT NULL,
    payload         JSONB       NOT NULL,
    effective_from  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by      BIGINT      REFERENCES app_user(id),
    note            TEXT,
    UNIQUE (scope, version)
);
COMMENT ON TABLE config_version IS '配置版本化，可回滚到任一历史版本（R-PLT-10）。';

-- =============================================================================
-- 1. 站点与层 / Site and layer  —— 唯一来源是 sites/*/site.yaml
-- =============================================================================

CREATE TABLE site (
    id              BIGSERIAL PRIMARY KEY,
    name            TEXT        NOT NULL UNIQUE,      -- photos / management / platform / account
    cloud           TEXT        NOT NULL,
    state_backend   TEXT        NOT NULL,             -- s3 / azblob
    gate            TEXT,                             -- 受管开关名，如 mgmt_enabled；无则 NULL
    manifest_sha    TEXT        NOT NULL,             -- site.yaml 的 git blob sha，用于发现漂移
    loaded_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    valid           BOOLEAN     NOT NULL DEFAULT TRUE,
    invalid_reason  TEXT,
    CONSTRAINT ck_site_cloud  CHECK (cloud IN ('aws', 'gcp', 'azure')),
    CONSTRAINT ck_site_backend CHECK (state_backend IN ('s3', 'azblob', 'gcs'))
);
COMMENT ON TABLE site IS '站点。manifest 校验失败时 valid=false，平台拒绝加载该站点而非猜测（R-INV-13）。';

CREATE TABLE layer (
    id              BIGSERIAL PRIMARY KEY,
    site_id         BIGINT      NOT NULL REFERENCES site(id) ON DELETE CASCADE,
    layer_id        TEXT        NOT NULL,             -- 目录名 00-bootstrap / 01-storage / 02-data ...
    layer_no        SMALLINT    NOT NULL,             -- 目录编号，同时就是 apply 顺序
    kind            TEXT        NOT NULL,
    depends_on      TEXT[]      NOT NULL DEFAULT '{}',
    state_key       TEXT,                             -- 如 photos/03-compute.tfstate
    gated_by        TEXT,                             -- 冗余 site.gate，便于单层查询
    UNIQUE (site_id, layer_id),
    UNIQUE (site_id, layer_no),
    CONSTRAINT ck_layer_kind CHECK (kind IN
        ('account', 'bootstrap', 'storage', 'data', 'compute', 'edge', 'observe')),
    CONSTRAINT ck_layer_no   CHECK (layer_no BETWEEN 0 AND 9)
);
CREATE INDEX ix_layer_order ON layer (site_id, layer_no);
COMMENT ON COLUMN layer.layer_id IS '目录名，栈标识。';
COMMENT ON COLUMN layer.layer_no IS '目录编号 = apply 顺序（ADR-002）。站点内连续、唯一、无空洞。';
COMMENT ON CONSTRAINT ck_layer_kind ON layer IS 'account 仅用于 s00-account 这一层。';

-- =============================================================================
-- 2. 资源域树 / Scope tree
-- =============================================================================

CREATE TABLE scope_node (
    id              BIGSERIAL PRIMARY KEY,
    path            LTREE       NOT NULL UNIQUE,
    parent_id       BIGINT      REFERENCES scope_node(id) ON DELETE CASCADE,
    kind            TEXT        NOT NULL,
    label           TEXT        NOT NULL,             -- 展示用原始名（保留大小写）
    cloud           TEXT        NOT NULL,
    site_id         BIGINT      REFERENCES site(id),
    layer_id        BIGINT      REFERENCES layer(id),
    sort_key        SMALLINT    NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_scope_kind CHECK (kind IN
        ('cloud', 'account', 'subscription', 'project', 'site', 'rg', 'layer', 'unassigned'))
);
CREATE INDEX ix_scope_path_gist ON scope_node USING GIST (path);
CREATE INDEX ix_scope_site      ON scope_node (site_id);

CREATE TABLE scope_node_alias (
    id              BIGSERIAL PRIMARY KEY,
    node_id         BIGINT      NOT NULL REFERENCES scope_node(id) ON DELETE CASCADE,
    old_path        LTREE       NOT NULL,
    changed_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    reason          TEXT        NOT NULL,
    UNIQUE (old_path, changed_at)
);
COMMENT ON TABLE scope_node_alias IS '节点改路径后保留旧路径，历史数据按旧路径仍可查（S3）。';

-- =============================================================================
-- 3. 资源 / Resource —— 版本化，当前版本由 superseded_by IS NULL 唯一确定
-- =============================================================================

CREATE TABLE resource (
    id              BIGSERIAL PRIMARY KEY,
    cloud           TEXT        NOT NULL,
    native_id       TEXT        NOT NULL,             -- ARN / 资源 ID / GCP selfLink，原样不解析
    native_type     TEXT        NOT NULL,             -- AWS::S3::Bucket 等
    canonical_type  TEXT        NOT NULL DEFAULT 'unknown',
    account_id      TEXT        NOT NULL,
    region          TEXT,
    metro           TEXT,
    name            TEXT,
    scope_id        BIGINT      NOT NULL REFERENCES scope_node(id),
    scope_path      LTREE       NOT NULL,             -- 冗余，便于子树查询
    site_id         BIGINT      REFERENCES site(id),
    layer_id        BIGINT      REFERENCES layer(id),
    layer_inferred  BOOLEAN     NOT NULL DEFAULT FALSE,
    tags_raw        JSONB       NOT NULL DEFAULT '{}'::jsonb,
    tags_canonical  JSONB       NOT NULL DEFAULT '{}'::jsonb,  -- 仅 6 个规范键
    attrs           JSONB       NOT NULL DEFAULT '{}'::jsonb,  -- 归一后的关键属性
    lifecycle_state TEXT        NOT NULL DEFAULT 'ACTIVE',
    first_seen_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    vanished_at     TIMESTAMPTZ,
    deleted_at      TIMESTAMPTZ,
    gone_at         TIMESTAMPTZ,
    superseded_by   BIGINT      REFERENCES resource(id),
    valid_from      TIMESTAMPTZ NOT NULL DEFAULT now(),
    valid_to        TIMESTAMPTZ,
    CONSTRAINT ck_res_cloud CHECK (cloud IN ('aws', 'gcp', 'azure')),
    CONSTRAINT ck_res_state CHECK (lifecycle_state IN
        ('ACTIVE', 'VANISHED', 'GONE', 'DELETED'))
);
-- 同一资源的当前版本唯一（R-INV-06）
CREATE UNIQUE INDEX ux_resource_current
    ON resource (cloud, native_id) WHERE superseded_by IS NULL;
CREATE INDEX ix_resource_scope  ON resource (scope_id);
CREATE INDEX ix_resource_layer  ON resource (site_id, layer_id) WHERE superseded_by IS NULL;
CREATE INDEX ix_resource_canon  ON resource (canonical_type) WHERE superseded_by IS NULL;
CREATE INDEX ix_resource_state  ON resource (lifecycle_state) WHERE lifecycle_state <> 'ACTIVE';
CREATE INDEX ix_resource_tags   ON resource USING GIN (tags_canonical jsonb_path_ops);
CREATE INDEX ix_resource_name   ON resource USING GIN (name gin_trgm_ops);
COMMENT ON COLUMN resource.canonical_type IS '只从 type_mapping 取，未命中为 unknown，不改写 native_type（R-INV-07）。';
COMMENT ON COLUMN resource.lifecycle_state IS 'VANISHED=查不到；DELETED=有删除事件（R-INV-05）。';

CREATE TABLE type_mapping (
    id              BIGSERIAL PRIMARY KEY,
    cloud           TEXT        NOT NULL,
    native_type     TEXT        NOT NULL,
    canonical_type  TEXT        NOT NULL,
    type_group      TEXT        NOT NULL,             -- storage/data/compute/edge/identity/observe
    note            TEXT,
    UNIQUE (cloud, native_type),
    CONSTRAINT ck_tm_group CHECK (type_group IN
        ('storage', 'data', 'compute', 'edge', 'identity', 'observe'))
);

CREATE TABLE tag_conflicts (
    id              BIGSERIAL PRIMARY KEY,
    cloud           TEXT        NOT NULL,
    native_id       TEXT        NOT NULL,
    canonical_key   TEXT        NOT NULL,
    variants        TEXT[]      NOT NULL,             -- 大小写不同的原始写法
    detected_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- =============================================================================
-- 4. IaC 快照 / IaC snapshots  —— 由 CI 产出，平台只消费
-- =============================================================================

CREATE TABLE iac_snapshot (
    id              BIGSERIAL PRIMARY KEY,
    site            TEXT        NOT NULL,
    layer_id        TEXT        NOT NULL,
    taken_at        TIMESTAMPTZ NOT NULL,
    source          TEXT        NOT NULL,             -- state / plan_refresh_only / plan
    blob_path       TEXT        NOT NULL,             -- snapshot/{site}/{layer}/{ts}.json
    terraform_sha   TEXT,                             -- state.json 的 serial / lineage
    resource_count  INT         NOT NULL DEFAULT 0,
    ingested_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_iac_source CHECK (source IN
        ('state', 'plan_refresh_only', 'plan'))
);
CREATE UNIQUE INDEX ux_iac_snapshot ON iac_snapshot (site, layer_id, taken_at, source);
CREATE INDEX ix_iac_latest ON iac_snapshot (site, layer_id, source, taken_at DESC);
COMMENT ON TABLE iac_snapshot IS '平台不直连 state 桶，只消费 terraform show -json 的产物（01 §12 I3）。';

CREATE TABLE iac_resource (
    id              BIGSERIAL PRIMARY KEY,
    snapshot_id     BIGINT      NOT NULL REFERENCES iac_snapshot(id) ON DELETE CASCADE,
    address         TEXT        NOT NULL,             -- module.app_deploy.aws_iam_role.app_deploy
    module_path     TEXT,
    native_type     TEXT        NOT NULL,
    cloud_native_id TEXT,
    attrs           JSONB       NOT NULL DEFAULT '{}'::jsonb
);
CREATE INDEX ix_iac_res_snap ON iac_resource (snapshot_id);
CREATE INDEX ix_iac_res_addr ON iac_resource (address);

-- =============================================================================
-- 5. 栈图谱 / Stack graph
-- =============================================================================

CREATE TABLE stack_edge (
    id              BIGSERIAL PRIMARY KEY,
    from_site       TEXT        NOT NULL,
    from_layer      TEXT        NOT NULL,
    to_site         TEXT        NOT NULL,
    to_layer        TEXT        NOT NULL,
    provenance      TEXT        NOT NULL,
    weight          NUMERIC(3,2) NOT NULL,
    evidence        TEXT        NOT NULL,             -- 人类可读的原文/位置
    evidence_ref    JSONB       NOT NULL DEFAULT '{}'::jsonb,
    hidden_by_default BOOLEAN   NOT NULL DEFAULT FALSE,
    broken          BOOLEAN     NOT NULL DEFAULT FALSE,
    computed_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_edge_prov CHECK (provenance IN
        ('STACK_STRUCTURE', 'TF_REMOTE_STATE', 'RESOURCE_REF', 'CONFIG_REF',
         'TRAFFIC', 'TAG_INFERRED')),
    CONSTRAINT ck_edge_weight CHECK (weight > 0 AND weight <= 1),
    UNIQUE (from_site, from_layer, to_site, to_layer, provenance)
);
CREATE INDEX ix_edge_from ON stack_edge (from_site, from_layer);
CREATE INDEX ix_edge_to   ON stack_edge (to_site, to_layer);
COMMENT ON COLUMN stack_edge.weight IS '结构/remote_state 1.00 > 引用 .95 > 配置 .90 > 流量 .85 > 标签 .70（R-GRP-02）。';
COMMENT ON COLUMN stack_edge.hidden_by_default IS '标签推断边默认不渲染（R-GRP-04）。';

CREATE TABLE stack_layout (
    site            TEXT        NOT NULL,
    layer_id        TEXT        NOT NULL,
    view_mode       TEXT        NOT NULL,             -- layer / flow / cost
    x               SMALLINT    NOT NULL,
    y               SMALLINT    NOT NULL,
    w               SMALLINT    NOT NULL,
    h               SMALLINT    NOT NULL,
    PRIMARY KEY (site, layer_id, view_mode),
    CONSTRAINT ck_layout_view CHECK (view_mode IN ('layer', 'flow', 'cost'))
);
COMMENT ON TABLE stack_layout IS '布局由后端给定，前端不跑力导向（R-GRP-10）。';

-- =============================================================================
-- 6. 漂移 / Drift
-- =============================================================================

CREATE TABLE drift_run (
    id              BIGSERIAL PRIMARY KEY,
    channel         TEXT        NOT NULL,             -- A / B
    started_at      TIMESTAMPTZ NOT NULL,
    finished_at     TIMESTAMPTZ,
    site            TEXT,
    layer_id        TEXT,
    status          TEXT        NOT NULL,
    checked_count   INT         NOT NULL DEFAULT 0,
    finding_count   INT         NOT NULL DEFAULT 0,
    error           TEXT,
    CONSTRAINT ck_drift_channel CHECK (channel IN ('A', 'B')),
    CONSTRAINT ck_drift_status  CHECK (status IN
        ('RUNNING', 'OK', 'PARTIAL', 'INCONCLUSIVE', 'FAILED'))
);
CREATE INDEX ix_drift_run_time ON drift_run (started_at DESC);
COMMENT ON COLUMN drift_run.status IS 'INCONCLUSIVE 必须区别于"0 条漂移"（R-DRF-09）。';

CREATE TABLE drift_finding (
    id              BIGSERIAL PRIMARY KEY,
    channel         TEXT        NOT NULL,
    resource_id     BIGINT      REFERENCES resource(id),
    cloud           TEXT        NOT NULL,
    native_id       TEXT        NOT NULL,
    site            TEXT,
    layer_id        TEXT,
    attribute_path  TEXT        NOT NULL,             -- tags.Lifecycle / MemorySize ...
    expected_value  JSONB       NOT NULL,
    actual_value    JSONB       NOT NULL,
    severity        TEXT        NOT NULL,
    class           TEXT,                             -- B 通道：CODE_BEHIND / UNCOMMITTED / STATE_DRIFT
    state           TEXT        NOT NULL DEFAULT 'OPEN',
    reason          TEXT,                             -- ACK / IGNORE 必填
    review_by       TIMESTAMPTZ,
    likely_run_id   BIGINT,
    first_seen_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    occurrence_count INT        NOT NULL DEFAULT 1,
    resolved_at     TIMESTAMPTZ,
    CONSTRAINT ck_drift_sev   CHECK (severity IN ('CRITICAL', 'HIGH', 'MEDIUM', 'LOW')),
    CONSTRAINT ck_drift_state CHECK (state IN
        ('OPEN', 'ACKNOWLEDGED', 'PENDING_VERIFY', 'RESOLVED', 'IGNORED', 'EXPIRED')),
    CONSTRAINT ck_drift_class CHECK (class IS NULL OR class IN
        ('CODE_BEHIND', 'UNCOMMITTED', 'STATE_DRIFT'))
);
CREATE UNIQUE INDEX ux_drift_open
    ON drift_finding (channel, cloud, native_id, attribute_path)
    WHERE state IN ('OPEN', 'ACKNOWLEDGED', 'PENDING_VERIFY');
CREATE INDEX ix_drift_sev   ON drift_finding (severity, state);
CREATE INDEX ix_drift_site  ON drift_finding (site, layer_id, state);
COMMENT ON INDEX ux_drift_open IS '同一 (资源, 属性) 只允许一条未关闭结论（R-DRF-07）。';

CREATE TABLE drift_ignore_rule (
    id              BIGSERIAL PRIMARY KEY,
    match_cloud     TEXT,
    match_native_type TEXT,
    match_path      TEXT        NOT NULL,             -- 支持 % 通配
    reason          TEXT        NOT NULL,
    expires_at      TIMESTAMPTZ NOT NULL,
    created_by      BIGINT      REFERENCES app_user(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE drift_ignore_rule IS '忽略必须有原因与到期日，到期自动失效（R-DRF-03 / R-DRF-12）。';

-- =============================================================================
-- 7. 成本 / Cost
-- =============================================================================

-- 7.1 原始账单行（S0）：字段保持各云原貌，只做最小映射
CREATE TABLE raw_cost_line (
    id              BIGSERIAL PRIMARY KEY,
    cloud           TEXT        NOT NULL,
    source          TEXT        NOT NULL,             -- cur|bq_export|azure_export|cost_explorer
    period_start    DATE        NOT NULL,
    payload         JSONB       NOT NULL,
    ingested_at     TIMESTAMPTZ NOT NULL DEFAULT now()
) PARTITION BY RANGE (period_start);
CREATE INDEX ix_raw_cost_cloud ON raw_cost_line (cloud, period_start);
COMMENT ON TABLE raw_cost_line IS '按 period_start 月分区；分区由 platform 任务的 ensure_partition() 提前 3 个月创建。';

-- 7.2 FOCUS 归一事实（S1）
CREATE TABLE cost_fact (
    id                  BIGSERIAL PRIMARY KEY,
    cloud               TEXT        NOT NULL,
    account_id          TEXT        NOT NULL,
    charge_period_start DATE        NOT NULL,
    charge_period_end   DATE        NOT NULL,
    service_name        TEXT        NOT NULL,         -- FOCUS ServiceName
    service_category    TEXT,                         -- 归一大类：compute/storage/network/...
    resource_id         TEXT,                         -- 可为空（共享成本）
    resource_type       TEXT,
    region              TEXT,
    metro               TEXT,
    charge_type         TEXT        NOT NULL,         -- Usage / Purchase / Tax / Credit / Adjustment
    charge_description  TEXT,
    sku                 TEXT,
    pricing_unit        TEXT,
    usage_quantity      NUMERIC(24,8),
    charged_cost        NUMERIC(20,6),
    effective_cost      NUMERIC(20,6),
    list_cost           NUMERIC(20,6),
    currency            TEXT        NOT NULL,
    fx_rate             NUMERIC(16,8),
    fx_date             DATE,
    basis               TEXT        NOT NULL,
    precision_level     TEXT        NOT NULL,
    tags                JSONB       NOT NULL DEFAULT '{}'::jsonb,
    raw_line_id         BIGINT      REFERENCES raw_cost_line(id),
    version             INT         NOT NULL DEFAULT 1,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_cost_basis CHECK (basis IN ('BILLED', 'AMORTIZED', 'ALLOCATED')),
    CONSTRAINT ck_cost_prec  CHECK (precision_level IN ('AGGREGATE', 'DETAIL', 'AMORTIZED'))
) PARTITION BY RANGE (charge_period_start);
CREATE UNIQUE INDEX ux_cost_fact_nat ON cost_fact
    (cloud, account_id, charge_period_start, service_name,
     coalesce(resource_id, ''), charge_type, basis, version);
CREATE INDEX ix_cost_fact_period ON cost_fact (charge_period_start, basis);
CREATE INDEX ix_cost_fact_res    ON cost_fact (resource_id);
CREATE INDEX ix_cost_fact_cloud  ON cost_fact (cloud, charge_period_start);
COMMENT ON TABLE cost_fact IS 'FOCUS 1.x 归一。金额可为 NULL 表示未知，禁止写 0（原则 P8）。';

-- 7.3 账期状态
CREATE TABLE cost_period (
    cloud           TEXT        NOT NULL,
    period_start    DATE        NOT NULL,
    status          TEXT        NOT NULL,             -- OPEN / PROVISIONAL / CLOSED
    official_total  NUMERIC(20,6),                    -- 该云官方汇总，用于 1% 偏差校验
    ingested_total  NUMERIC(20,6),
    deviation_pct   NUMERIC(8,4),
    closed_at       TIMESTAMPTZ,
    PRIMARY KEY (cloud, period_start),
    CONSTRAINT ck_period_status CHECK (status IN ('OPEN', 'PROVISIONAL', 'CLOSED'))
);
COMMENT ON TABLE cost_period IS 'T+2 后仍缺行或偏差 >1% 标 PROVISIONAL（R-FIN-09）。';

-- 7.4 分摊规则（版本化）
CREATE TABLE allocation_rule (
    id              BIGSERIAL PRIMARY KEY,
    version         INT         NOT NULL UNIQUE,
    payload         JSONB       NOT NULL,             -- 四步分摊的权重与兜底
    effective_from  TIMESTAMPTZ NOT NULL,
    effective_to    TIMESTAMPTZ,
    note            TEXT
);

-- 7.5 分摊结果（S3，派生，可重建）
CREATE TABLE allocation (
    id              BIGSERIAL PRIMARY KEY,
    period_start    DATE        NOT NULL,
    cloud           TEXT        NOT NULL,
    site            TEXT,
    layer_id        TEXT,
    canonical_type  TEXT,
    service_category TEXT,
    amount          NUMERIC(20,6),
    currency        TEXT        NOT NULL,
    basis           TEXT        NOT NULL,
    step            SMALLINT    NOT NULL,             -- 1..4，命中分摊的第几步
    rule_version    INT         NOT NULL,
    built_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_alloc_step CHECK (step BETWEEN 1 AND 4),
    CONSTRAINT ck_alloc_basis CHECK (basis IN ('BILLED', 'AMORTIZED', 'ALLOCATED'))
) PARTITION BY RANGE (period_start);
CREATE INDEX ix_alloc_period ON allocation (period_start, basis);
CREATE INDEX ix_alloc_site   ON allocation (site, layer_id);
COMMENT ON COLUMN allocation.step IS '1 资源直挂 / 2 规范标签 / 3 scope 推断 / 4 unallocated（R-FIN-07）。';

-- 7.6 差异桥（S4）
CREATE TABLE cost_bridge (
    id              BIGSERIAL PRIMARY KEY,
    period_start    DATE        NOT NULL,
    cloud           TEXT        NOT NULL,
    currency        TEXT        NOT NULL,
    billed_total    NUMERIC(20,6),
    allocated_total NUMERIC(20,6),
    -- 五类差额，逐项可解释（R-FIN-05）
    shared_unallocated  NUMERIC(20,6),
    commitment_unamort  NUMERIC(20,6),
    cross_period_adj    NUMERIC(20,6),
    tax                 NUMERIC(20,6),
    fx_diff             NUMERIC(20,6),
    built_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (period_start, cloud, currency)
);
COMMENT ON TABLE cost_bridge IS '五类之和必须等于 billed_total - allocated_total，误差 0（R-FIN-05 验收）。';

-- 7.7 预算与节省
CREATE TABLE cost_budget (
    id              BIGSERIAL PRIMARY KEY,
    site            TEXT        NOT NULL,
    layer_id        TEXT,
    period_month    DATE        NOT NULL,             -- 当月 1 日
    amount          NUMERIC(20,6) NOT NULL,
    currency        TEXT        NOT NULL DEFAULT 'CNY',
    UNIQUE (site, layer_id, period_month)
);

CREATE TABLE saving_suggestion (
    id              BIGSERIAL PRIMARY KEY,
    kind            TEXT        NOT NULL,             -- IDLE / UNATTACHED / OVER_COMMIT / CROSS_ZONE
    resource_id     BIGINT      REFERENCES resource(id),
    site            TEXT,
    layer_id        TEXT,
    estimated_saving NUMERIC(20,6),
    currency        TEXT        NOT NULL,
    formula         TEXT        NOT NULL,             -- 估算公式，必须可读可复算
    evidence        JSONB       NOT NULL,             -- 依据数据
    state           TEXT        NOT NULL DEFAULT 'OPEN',
    detected_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_saving_kind CHECK (kind IN
        ('IDLE', 'UNATTACHED', 'OVER_COMMIT', 'CROSS_ZONE')),
    CONSTRAINT ck_saving_state CHECK (state IN ('OPEN', 'ACCEPTED', 'DISMISSED', 'DONE'))
);

-- =============================================================================
-- 8. 变更与发布 / Change and delivery
-- =============================================================================

CREATE TABLE change_event (
    id              BIGSERIAL PRIMARY KEY,
    cloud           TEXT        NOT NULL,
    occurred_at     TIMESTAMPTZ NOT NULL,
    actor           TEXT,
    actor_type      TEXT,                             -- user / role / service
    event_name      TEXT        NOT NULL,             -- DeleteBucket / SetIamPolicy
    native_id       TEXT,
    source_ip       TEXT,
    raw             JSONB       NOT NULL DEFAULT '{}'::jsonb
);
CREATE INDEX ix_change_time ON change_event (occurred_at DESC);
CREATE INDEX ix_change_res  ON change_event (cloud, native_id, occurred_at DESC);

CREATE TABLE workflow_run (
    id              BIGSERIAL PRIMARY KEY,
    provider        TEXT        NOT NULL DEFAULT 'github',
    repo            TEXT        NOT NULL,
    run_id          BIGINT      NOT NULL,
    workflow        TEXT        NOT NULL,
    event           TEXT        NOT NULL,             -- pull_request / push / workflow_dispatch
    mode            TEXT,                             -- plan / apply
    status          TEXT        NOT NULL,
    conclusion      TEXT,
    head_sha        TEXT,
    pr_number       INT,
    actor           TEXT,
    started_at      TIMESTAMPTZ,
    finished_at     TIMESTAMPTZ,
    url             TEXT,
    UNIQUE (repo, run_id),
    CONSTRAINT ck_wf_mode CHECK (mode IS NULL OR mode IN ('plan', 'apply'))
);
CREATE INDEX ix_wf_time ON workflow_run (started_at DESC);
CREATE INDEX ix_wf_repo ON workflow_run (repo, status);

CREATE TABLE workflow_job (
    id              BIGSERIAL PRIMARY KEY,
    run_pk          BIGINT      NOT NULL REFERENCES workflow_run(id) ON DELETE CASCADE,
    job_id          BIGINT      NOT NULL,
    name            TEXT        NOT NULL,
    site            TEXT,
    layer_id        TEXT,
    status          TEXT        NOT NULL,
    conclusion      TEXT,
    started_at      TIMESTAMPTZ,
    finished_at     TIMESTAMPTZ,
    UNIQUE (run_pk, job_id)
);

CREATE TABLE resource_change (
    id              BIGSERIAL PRIMARY KEY,
    run_pk          BIGINT      REFERENCES workflow_run(id),
    site            TEXT        NOT NULL,
    layer_id        TEXT        NOT NULL,
    cloud           TEXT,
    native_id       TEXT,
    address         TEXT,
    action          TEXT        NOT NULL,             -- ADDED / CHANGED / DESTROYED / REPLACED
    before_attrs    JSONB,
    after_attrs     JSONB,
    detected_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_rc_action CHECK (action IN
        ('ADDED', 'CHANGED', 'DESTROYED', 'REPLACED'))
);
CREATE INDEX ix_rc_run ON resource_change (run_pk);
CREATE INDEX ix_rc_res ON resource_change (cloud, native_id, detected_at DESC);

CREATE TABLE dispatch_request (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    site            TEXT        NOT NULL,
    layers          TEXT[]      NOT NULL,
    requested_by    BIGINT      NOT NULL REFERENCES app_user(id),
    confirmed_by    BIGINT      REFERENCES app_user(id),
    plan_summary    JSONB       NOT NULL,
    risk_level      TEXT,
    state           TEXT        NOT NULL DEFAULT 'PENDING',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    confirmed_at    TIMESTAMPTZ,
    run_pk          BIGINT      REFERENCES workflow_run(id),
    CONSTRAINT ck_disp_state CHECK (state IN
        ('PENDING', 'CONFIRMED', 'DISPATCHED', 'REJECTED', 'EXPIRED')),
    CONSTRAINT ck_disp_two_man CHECK (confirmed_by IS NULL OR confirmed_by <> requested_by)
);
COMMENT ON TABLE dispatch_request IS '双人确认：发起人与确认人必须不同（R-DEL-05）。';

-- =============================================================================
-- 9. 观测 / Observability（只留 24h 快照）
-- =============================================================================

CREATE TABLE metric_snapshot (
    id              BIGSERIAL PRIMARY KEY,
    site            TEXT        NOT NULL,
    layer_id        TEXT,
    metric_key      TEXT        NOT NULL,             -- 预定义键，非任意查询
    captured_at     TIMESTAMPTZ NOT NULL,
    value           NUMERIC(20,6),
    unit            TEXT,
    source          TEXT        NOT NULL,
    CONSTRAINT ck_metric_unit CHECK (unit IS NULL OR length(unit) <= 16)
);
CREATE UNIQUE INDEX ux_metric_point
    ON metric_snapshot (site, coalesce(layer_id, ''), metric_key, captured_at);
CREATE INDEX ix_metric_time ON metric_snapshot (captured_at DESC);
COMMENT ON TABLE metric_snapshot IS '仅保留 24 小时，每 5 分钟一点；长期数据在原生系统（R-OBS-05）。';

CREATE TABLE alert_aggregate (
    id              BIGSERIAL PRIMARY KEY,
    site            TEXT,
    layer_id        TEXT,
    severity        TEXT        NOT NULL,
    title           TEXT        NOT NULL,
    first_seen_at   TIMESTAMPTZ NOT NULL,
    last_seen_at    TIMESTAMPTZ NOT NULL,
    occurrence_count INT        NOT NULL DEFAULT 1,
    state           TEXT        NOT NULL DEFAULT 'OPEN',
    source_link     TEXT,
    CONSTRAINT ck_alert_sev   CHECK (severity IN ('CRITICAL', 'HIGH', 'MEDIUM', 'LOW')),
    CONSTRAINT ck_alert_state CHECK (state IN ('OPEN', 'ACKED', 'CLOSED'))
);
CREATE INDEX ix_alert_open ON alert_aggregate (severity, state, last_seen_at DESC);

CREATE TABLE slo_definition (
    id              BIGSERIAL PRIMARY KEY,
    key             TEXT        NOT NULL UNIQUE,      -- site_availability / api_p95 / queue_backlog
    target          NUMERIC(6,4) NOT NULL,
    window_days     SMALLINT    NOT NULL DEFAULT 30,
    config          JSONB       NOT NULL DEFAULT '{}'::jsonb
);

-- =============================================================================
-- 10. 安全 / Security
-- =============================================================================

CREATE TABLE security_finding (
    id              BIGSERIAL PRIMARY KEY,
    rule_key        TEXT        NOT NULL,             -- exposure.public_bucket / crypto.tls_version ...
    rule_version    TEXT        NOT NULL,
    class           TEXT        NOT NULL,
    severity        TEXT        NOT NULL,
    resource_id     BIGINT      REFERENCES resource(id),
    cloud           TEXT,
    native_id       TEXT,
    title           TEXT        NOT NULL,
    evidence        JSONB       NOT NULL,             -- 属性路径 / 文件行（密钥原文不落库）
    recommendation  TEXT        NOT NULL,
    state           TEXT        NOT NULL DEFAULT 'OPEN',
    first_seen_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_sec_class CHECK (class IN
        ('EXPOSURE', 'ENCRYPTION', 'IDENTITY', 'SECRET_LOCATION')),
    CONSTRAINT ck_sec_sev   CHECK (severity IN ('CRITICAL', 'HIGH', 'MEDIUM', 'LOW')),
    CONSTRAINT ck_sec_state CHECK (state IN ('OPEN', 'WAIVED', 'RESOLVED'))
);
CREATE UNIQUE INDEX ux_sec_open
    ON security_finding (rule_key, coalesce(cloud, ''), coalesce(native_id, ''))
    WHERE state = 'OPEN';
CREATE INDEX ix_sec_state ON security_finding (state, severity);

CREATE TABLE security_waiver (
    id              BIGSERIAL PRIMARY KEY,
    finding_id      BIGINT      NOT NULL REFERENCES security_finding(id) ON DELETE CASCADE,
    reason          TEXT        NOT NULL,
    expires_at      TIMESTAMPTZ NOT NULL,
    created_by      BIGINT      REFERENCES app_user(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE security_waiver IS '豁免到期自动复现（R-SEC-06）。';

-- =============================================================================
-- 11. 通知 / Notification
-- =============================================================================

CREATE TABLE notify_subscription (
    id              BIGSERIAL PRIMARY KEY,
    channel         TEXT        NOT NULL,             -- feishu / smtp / inapp
    target          TEXT        NOT NULL,             -- webhook 或邮箱
    event_types     TEXT[]      NOT NULL,
    min_severity    TEXT        NOT NULL DEFAULT 'HIGH',
    site_filter     TEXT[],
    quiet_windows   JSONB       NOT NULL DEFAULT '[]'::jsonb,
    active          BOOLEAN     NOT NULL DEFAULT TRUE,
    CONSTRAINT ck_notify_channel CHECK (channel IN ('feishu', 'smtp', 'inapp')),
    CONSTRAINT ck_notify_sev CHECK (min_severity IN ('CRITICAL', 'HIGH', 'MEDIUM', 'LOW'))
);

CREATE TABLE notify_delivery (
    id              BIGSERIAL PRIMARY KEY,
    subscription_id BIGINT      REFERENCES notify_subscription(id),
    event_type      TEXT        NOT NULL,
    dedupe_key      TEXT        NOT NULL,
    payload         JSONB       NOT NULL,
    state           TEXT        NOT NULL DEFAULT 'PENDING',
    attempts        SMALLINT    NOT NULL DEFAULT 0,
    last_error      TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at         TIMESTAMPTZ,
    CONSTRAINT ck_notify_state CHECK (state IN
        ('PENDING', 'SENT', 'FAILED', 'SUPPRESSED')),
    CONSTRAINT ck_notify_attempts CHECK (attempts <= 3)
);
CREATE INDEX ix_notify_dedupe ON notify_delivery (dedupe_key, created_at DESC);

-- =============================================================================
-- 12. 容灾矩阵与演练 / DR matrix and drills
-- =============================================================================

CREATE TABLE dr_component (
    id              BIGSERIAL PRIMARY KEY,
    name            TEXT        NOT NULL UNIQUE,
    primary_spec    TEXT        NOT NULL,
    standby_spec    TEXT,
    switch_method   TEXT        NOT NULL,             -- dns_failover / promote / restore
    rto_minutes     INT,
    rpo_minutes     INT,
    preconditions   JSONB       NOT NULL DEFAULT '[]'::jsonb,  -- [{text, verified}]
    last_drill_at   TIMESTAMPTZ,
    verified        BOOLEAN     NOT NULL DEFAULT FALSE,
    note            TEXT
);
COMMENT ON COLUMN dr_component.verified IS 'RTO/RPO 只有在演练验证后才算数，未验证的显示"未验证"（R-PLT-13）。';

CREATE TABLE dr_drill (
    id              BIGSERIAL PRIMARY KEY,
    conducted_at    TIMESTAMPTZ NOT NULL,
    scope           TEXT        NOT NULL,             -- 演练覆盖范围（自由文本）
    actual_rto_minutes INT,
    actual_rpo_minutes INT,
    outcome         TEXT        NOT NULL,             -- PASS / PARTIAL / FAIL
    issues          TEXT,
    deviation       TEXT,
    followups       JSONB       NOT NULL DEFAULT '[]'::jsonb,
    recorded_by     BIGINT      REFERENCES app_user(id),
    CONSTRAINT ck_drill_outcome CHECK (outcome IN ('PASS', 'PARTIAL', 'FAIL'))
);

CREATE TABLE dr_drill_component (
    drill_id        BIGINT      NOT NULL REFERENCES dr_drill(id) ON DELETE CASCADE,
    component_id    BIGINT      NOT NULL REFERENCES dr_component(id) ON DELETE CASCADE,
    PRIMARY KEY (drill_id, component_id)
);

-- =============================================================================
-- 13. 任务运行 / Job runs
-- =============================================================================

CREATE TABLE job_run (
    id              BIGSERIAL PRIMARY KEY,
    job_name        TEXT        NOT NULL,
    scope_key       TEXT        NOT NULL,             -- aws:156487532333 / gcp:album-backup / github:album-infra
    started_at      TIMESTAMPTZ NOT NULL,
    finished_at     TIMESTAMPTZ,
    status          TEXT        NOT NULL,             -- RUNNING / OK / PARTIAL / FAILED / TIMEOUT
    rows_in         BIGINT      NOT NULL DEFAULT 0,
    rows_out        BIGINT      NOT NULL DEFAULT 0,
    error           TEXT,
    detail          JSONB       NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT ck_job_status CHECK (status IN
        ('RUNNING', 'OK', 'PARTIAL', 'FAILED', 'TIMEOUT'))
);
CREATE INDEX ix_job_run_time ON job_run (job_name, started_at DESC);
CREATE INDEX ix_job_run_fail ON job_run (status) WHERE status IN ('FAILED', 'TIMEOUT');

-- ShedLock 表（与 ShedLock 4.x 默认 DDL 一致）
CREATE TABLE shedlock (
    name        TEXT        PRIMARY KEY,
    lock_until  TIMESTAMP   NOT NULL,
    locked_at   TIMESTAMP   NOT NULL,
    locked_by   TEXT        NOT NULL
);

-- Spring Modulith Event Publication Registry
CREATE TABLE event_publication (
    id                  UUID        PRIMARY KEY,
    listener_id         TEXT        NOT NULL,
    event_type          TEXT        NOT NULL,
    serialized_event    TEXT        NOT NULL,
    publication_date    TIMESTAMPTZ NOT NULL,
    completion_date     TIMESTAMPTZ
);
CREATE INDEX ix_event_pub_incomplete
    ON event_publication (completion_date) WHERE completion_date IS NULL;

-- =============================================================================
-- 14. 视图 / Views
-- =============================================================================

-- 14.1 当前版本资源
CREATE VIEW v_resource_current AS
SELECT r.*
FROM resource r
WHERE r.superseded_by IS NULL;

-- 14.2 层类型适配度（R-INV-14）
CREATE VIEW v_layer_fit AS
SELECT r.site_id,
       r.layer_id,
       l.layer_id                        AS layer_dir,
       l.kind                            AS layer_kind,
       r.canonical_type,
       count(*)                          AS resource_count,
       CASE
         WHEN l.kind = 'bootstrap' AND tm.type_group IN ('identity', 'storage', 'observe') THEN 1
         WHEN l.kind = 'storage'   AND tm.type_group = 'storage'   THEN 1
         WHEN l.kind = 'data'      AND tm.type_group = 'data'      THEN 1
         WHEN l.kind = 'compute'   AND tm.type_group IN ('compute', 'data') THEN 1
         WHEN l.kind = 'edge'      AND tm.type_group = 'edge'      THEN 1
         WHEN l.kind = 'observe'   AND tm.type_group = 'observe'   THEN 1
         ELSE 0
       END                               AS fit
FROM resource r
JOIN layer l ON l.id = r.layer_id
LEFT JOIN type_mapping tm
       ON tm.cloud = r.cloud AND tm.native_type = r.native_type
WHERE r.superseded_by IS NULL
  AND r.lifecycle_state = 'ACTIVE'
GROUP BY r.site_id, r.layer_id, l.layer_id, l.kind, r.canonical_type, tm.type_group;

-- 14.3 目录健康度（R-INV-02 / R-INV-11）
CREATE VIEW v_inventory_health AS
SELECT
    count(*)                                                                   AS total,
    count(*) FILTER (WHERE scope_path <@ 'unassigned'::ltree)                  AS unassigned,
    count(*) FILTER (WHERE canonical_type = 'unknown')                         AS unknown_type,
    count(*) FILTER (WHERE layer_id IS NULL)                                   AS no_layer,
    count(*) FILTER (WHERE tags_canonical ->> 'app' IS NULL)                   AS missing_app_tag,
    count(*) FILTER (WHERE lifecycle_state <> 'ACTIVE')                        AS not_active,
    max(last_seen_at)                                                          AS freshest_at
FROM resource
WHERE superseded_by IS NULL;

-- 14.4 栈成本（派生，随 allocation 重建）
CREATE VIEW v_stack_cost AS
SELECT a.site,
       a.layer_id,
       a.period_start,
       a.basis,
       sum(a.amount) FILTER (WHERE a.amount IS NOT NULL)                       AS known_amount,
       sum(a.amount)                                                          AS total_amount,
       count(*) FILTER (WHERE a.amount IS NULL)                                AS unknown_rows
FROM allocation a
GROUP BY a.site, a.layer_id, a.period_start, a.basis;

-- 14.5 漂移覆盖率（R-DRF-11）
CREATE VIEW v_drift_coverage AS
SELECT r.cloud,
       r.site_id,
       r.layer_id,
       count(*)                                                               AS managed,
       count(*) FILTER (WHERE r.last_seen_at > now() - interval '24 hours')    AS checked_recently
FROM resource r
WHERE r.superseded_by IS NULL AND r.lifecycle_state = 'ACTIVE'
GROUP BY r.cloud, r.site_id, r.layer_id;

-- 14.6 事件死信
CREATE VIEW v_event_dead AS
SELECT *
FROM event_publication
WHERE completion_date IS NULL
  AND publication_date < now() - interval '30 minutes';

-- =============================================================================
-- 15. 分区管理 / Partition helper
--    cost_fact / allocation / raw_cost_line 按月分区，任务提前 3 个月创建
-- =============================================================================

CREATE OR REPLACE FUNCTION ensure_month_partition(p_table TEXT, p_month DATE)
RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE
    v_start DATE := date_trunc('month', p_month)::date;
    v_end   DATE := (date_trunc('month', p_month) + interval '1 month')::date;
    v_name  TEXT := format('%s_p%s', p_table, to_char(v_start, 'YYYYMM'));
BEGIN
    IF to_regclass(v_name) IS NULL THEN
        EXECUTE format(
            'CREATE TABLE %I PARTITION OF %I FOR VALUES FROM (%L) TO (%L)',
            v_name, p_table, v_start, v_end);
    END IF;
END;
$$;
COMMENT ON FUNCTION ensure_month_partition(TEXT, DATE)
    IS '幂等：分区已存在则跳过。由 platform 的日频任务调用。';

-- =============================================================================
-- 16. 初始数据 / Seed
-- =============================================================================

INSERT INTO type_mapping (cloud, native_type, canonical_type, type_group) VALUES
  ('aws',   'AWS::S3::Bucket',                    'storage.object',     'storage'),
  ('aws',   'AWS::EBS::Volume',                   'storage.block',      'storage'),
  ('aws',   'AWS::EFS::FileSystem',               'filesystem',         'storage'),
  ('aws',   'AWS::DynamoDB::Table',               'data.kv',            'data'),
  ('aws',   'AWS::RDS::DBInstance',               'data.rdbms',         'data'),
  ('aws',   'AWS::SQS::Queue',                    'data.queue',         'data'),
  ('aws',   'AWS::Lambda::Function',              'compute.serverless', 'compute'),
  ('aws',   'AWS::ECS::Service',                  'compute.container',  'compute'),
  ('aws',   'AWS::EC2::Instance',                 'compute.vm',         'compute'),
  ('aws',   'AWS::ElasticLoadBalancingV2::LoadBalancer', 'compute.lb',  'compute'),
  ('aws',   'AWS::ApiGatewayV2::Api',             'compute.api',        'compute'),
  ('aws',   'AWS::CloudFront::Distribution',      'edge.cdn',           'edge'),
  ('aws',   'AWS::Route53::HostedZone',           'edge.dns',           'edge'),
  ('aws',   'AWS::CertificateManager::Certificate','edge.cert',         'edge'),
  ('aws',   'AWS::WAFv2::WebACL',                 'edge.waf',           'edge'),
  ('aws',   'AWS::IAM::Role',                     'identity.role',      'identity'),
  ('aws',   'AWS::IAM::Policy',                   'identity.policy',    'identity'),
  ('aws',   'AWS::KMS::Key',                      'identity.kms',       'identity'),
  ('aws',   'AWS::ECR::Repository',               'registry.container', 'storage'),
  ('aws',   'AWS::Logs::LogGroup',                'observe.log',        'observe'),
  ('aws',   'AWS::CloudWatch::Alarm',             'observe.alarm',      'observe'),
  ('gcp',   'storage.googleapis.com/Bucket',      'storage.object',     'storage'),
  ('gcp',   'compute.googleapis.com/Instance',    'compute.vm',         'compute'),
  ('gcp',   'container.googleapis.com/Cluster',   'compute.container',  'compute'),
  ('gcp',   'run.googleapis.com/Service',         'compute.serverless', 'compute'),
  ('gcp',   'sqladmin.googleapis.com/Instance',   'data.rdbms',         'data'),
  ('gcp',   'bigquery.googleapis.com/Dataset',    'data.warehouse',     'data'),
  ('gcp',   'pubsub.googleapis.com/Topic',        'data.queue',         'data'),
  ('gcp',   'dns.googleapis.com/ManagedZone',     'edge.dns',           'edge'),
  ('gcp',   'compute.googleapis.com/BackendService', 'compute.lb',      'compute'),
  ('azure', 'Microsoft.Storage/storageAccounts',  'storage.object',     'storage'),
  ('azure', 'Microsoft.Compute/virtualMachines',  'compute.vm',         'compute'),
  ('azure', 'Microsoft.App/containerApps',        'compute.container',  'compute'),
  ('azure', 'Microsoft.DBforPostgreSQL/flexibleServers', 'data.rdbms',  'data'),
  ('azure', 'Microsoft.KeyVault/vaults',          'identity.kms',       'identity'),
  ('azure', 'Microsoft.Cdn/profiles',             'edge.cdn',           'edge'),
  ('azure', 'Microsoft.Network/dnsZones',         'edge.dns',           'edge'),
  ('azure', 'Microsoft.OperationalInsights/workspaces', 'observe.log',  'observe'),
  ('azure', 'Microsoft.ContainerRegistry/registries', 'registry.container', 'storage');

INSERT INTO allocation_rule (version, payload, effective_from, note) VALUES
  (1,
   '{"steps":[{"id":1,"name":"resource_direct","source":"cost_fact.resource_id"},
              {"id":2,"name":"canonical_tag","keys":["app","site","layer"]},
              {"id":3,"name":"scope_inference","source":"resource.scope_path"},
              {"id":4,"name":"unallocated"}],
     "fallback":"unallocated"}'::jsonb,
   '2026-01-01T00:00:00Z',
   '首版四步分摊，不跳步。');

INSERT INTO slo_definition (key, target, window_days) VALUES
  ('site_availability', 0.9950, 30),
  ('api_p95_latency',   0.9500, 30),
  ('queue_backlog',     0.9900, 30);

-- =============================================================================
-- End of V1__core.sql
-- =============================================================================
