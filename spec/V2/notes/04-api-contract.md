# 04 · API 契约 / API Contract

| 项 / Item | 值 / Value |
| --- | --- |
| 状态 / Status | Approved for implementation |
| 版本 / Version | `v1` |
| 基址 / Base | `/api/v1` |
| 内容类型 / Content type | `application/json; charset=utf-8` |
| 依赖 / Depends on | `02`（规则）、`03`（数据模型） |

---

## 1. 通用约定 / Conventions

### 1.1 认证

| 项 | 规定 |
| --- | --- |
| 方式 | `Authorization: Bearer <session-token>`（服务端会话，非 JWT 自包含） |
| 获取 | `POST /api/v1/auth/login`，返回 `token` + `expiresAt` |
| 有效期 | 8 小时，滑动续期（剩余 < 1 小时时自动续到 8 小时） |
| 敏感操作 | `POST /dispatch`、`GET /cost/export`、`PUT /platform/config` 需要 `X-Elevated: true`，且会话 `elevated_until > now()`；否则返回 `403 ELEVATION_REQUIRED` |
| 角色 | `OWNER` 全权；`VIEWER` 只读，写端点返回 `403 FORBIDDEN` |

### 1.2 统一响应包

成功：

```json
{ "data": { }, "meta": { } }
```

列表：

```json
{
  "data": [ ],
  "meta": { "page": 0, "size": 50, "total": 137, "hasNext": true },
  "asOf": "2026-11-16T02:15:03Z"
}
```

失败：

```json
{
  "error": {
    "code": "DRIFT_FINDING_NOT_FOUND",
    "message": "漂移结论不存在或已删除",
    "details": { "id": "9821" },
    "traceId": "b7f1c9e2"
  }
}
```

三条硬规定：

| # | 规定 |
| --- | --- |
| A1 | 所有时间 ISO-8601 UTC，带 `Z`。服务端不接受本地时间字符串。 |
| A2 | 所有金额 `{"amount": 1234.5678, "currency": "CNY", "basis": "AMORTIZED", "precision": "DETAIL"}` 四元组，缺任一字段视为契约违规。`amount` 可为 `null`。 |
| A3 | `asOf` 表示数据的真实新鲜度，**不是**响应生成时间。前端必须展示它。 |

### 1.3 分页与排序

| 参数 | 默认 | 说明 |
| --- | --- | --- |
| `page` | 0 | 从 0 开始 |
| `size` | 50 | 上限 200 |
| `sort` | 端点默认 | 形如 `severity:desc,firstSeenAt:asc`，只允许白名单字段 |

超过 5000 行的导出走导出端点，不走分页。

### 1.4 错误码

| HTTP | code | 场景 |
| --- | --- | --- |
| 400 | `VALIDATION_FAILED` | 参数缺失/越界，`details` 给出字段级原因 |
| 401 | `UNAUTHENTICATED` | 无 token 或已过期 |
| 403 | `FORBIDDEN` | 角色不足 |
| 403 | `ELEVATION_REQUIRED` | 需要二次确认 |
| 403 | `TWO_MAN_REQUIRED` | 触发 apply 缺少第二人确认 |
| 404 | `*_NOT_FOUND` | 资源不存在 |
| 409 | `STATE_CONFLICT` | 结论已被他人认领 / 版本号过期 |
| 422 | `PRECONDITION_FAILED` | 业务前置条件不满足（如 `plan` 摘要未知禁止触发） |
| 429 | `RATE_LIMITED` | 手动触发类端点限流（10 次/分钟） |
| 503 | `SOURCE_DEGRADED` | 依赖的云或 GitHub 不可用，返回可用的降级数据 |
| 500 | `INTERNAL` | 兜底，必须带 `traceId` |

### 1.5 幂等与并发

- 所有 `POST` 创建类端点接受 `Idempotency-Key` 头，24 小时内同 key 返回首次结果。
- 更新类端点用乐观锁：请求体带 `version`，不匹配返回 `409 STATE_CONFLICT`。
- 手动触发采集（`/platform/jobs/{name}/run`）100 秒内同 `(job, scope)` 只执行一次，重复调用返回同一 `jobRunId`。

### 1.6 降级响应

当数据源不可用时**不返回 5xx**，返回 200 + `degraded` 标记，让页面仍可读：

```json
{
  "data": { },
  "degraded": [
    { "source": "aws", "reason": "ResourceExplorer 调用失败", "since": "2026-11-16T00:10:00Z", "lastSuccessAt": "2026-11-15T23:55:00Z" }
  ],
  "asOf": "2026-11-15T23:55:00Z"
}
```

前端遇到 `degraded` 非空必须显示横幅（`05` §3）。

---

## 2. 认证与会话 / Auth

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/auth/login` | body `{username, password}` → `{token, expiresAt, user{id,username,role}}` |
| `POST` | `/auth/logout` | 撤销当前会话 |
| `POST` | `/auth/elevate` | body `{password}` → 置 `elevated_until = now()+5min` |
| `GET` | `/auth/me` | 当前用户与角色、`elevatedUntil` |

登录失败 5 次锁 15 分钟，返回 `429 RATE_LIMITED`。

---

## 3. 资源目录 / Inventory

### 3.1 `GET /inventory/resources`

| 参数 | 类型 | 说明 |
| --- | --- | --- |
| `site` `layer` `cloud` | string | 可重复 |
| `type` | string | `canonical_type`，支持前缀（`storage.*`） |
| `state` | string | `ACTIVE/VANISHED/GONE/DELETED` |
| `q` | string | 名称、native_id 模糊匹配（trigram） |
| `fit` | 0/1 | 按层类型适配度过滤 |
| `tag` | string | `app=album` 形式 |

响应项：

```json
{
  "cloud": "aws",
  "nativeId": "arn:aws:s3:::chenyouyi",
  "nativeType": "AWS::S3::Bucket",
  "canonicalType": "storage.object",
  "name": "chenyouyi",
  "accountId": "156487532333",
  "region": "ap-southeast-1",
  "metro": "singapore",
  "scope": { "path": "aws.156487532333.photos.l_01_storage", "label": "photos / 01-storage" },
  "site": "photos",
  "layer": "01-storage",
  "layerInferred": false,
  "lifecycleState": "ACTIVE",
  "tags": { "app": "album", "site": "photos", "layer": "01-storage", "env": "prod" },
  "monthlyCost": { "amount": 12.43, "currency": "USD", "basis": "AMORTIZED", "precision": "DETAIL" },
  "lastSeenAt": "2026-11-16T02:15:00Z",
  "driftCount": 1
}
```

### 3.2 `GET /inventory/resources/{cloud}/{nativeId}`

URL-encode `nativeId`（ARN 含 `:` 和 `/`）。响应额外包含：

| 字段 | 说明 |
| --- | --- |
| `attrs` | 归一后关键属性 |
| `attrsHistory` | 最近 10 次变更（`changedAt`、`changedPaths`、`before/after`） |
| `iac` | `{ address, snapshotAt, layer }` — 代码侧描述 |
| `costs` | 最近 3 个月成本，按口径 |
| `drifts` | 未关闭漂移摘要 |
| `security` | 未豁免安全结论摘要 |
| `changes` | 最近 10 次云侧变更事件 |

### 3.3 `GET /inventory/tree`

```json
{
  "data": [
    { "path": "aws", "kind": "cloud", "label": "AWS", "resourceCount": 812, "cost": null, "children": [
      { "path": "aws.156487532333", "kind": "account", "label": "156487532333", "resourceCount": 812,
        "children": [
          { "path": "aws.156487532333.photos", "kind": "site", "label": "photos", "resourceCount": 640,
            "children": [
              { "path": "aws.156487532333.photos.l_01_storage", "kind": "layer", "label": "01-storage",
                "resourceCount": 12, "cost": { } }
            ] }
        ] }
    ] }
  ]
}
```

### 3.4 `GET /inventory/stacks`

返回全部 stack，含 `site / layerId / layerNo / kind / cloud / deprecations`；字段见 `01` §8.1。

### 3.5 `GET /inventory/health`

```json
{
  "data": {
    "total": 1834,
    "unassigned": 21,
    "unknownType": 7,
    "noLayer": 9,
    "missingAppTag": 46,
    "notActive": 63,
    "freshestAt": "2026-11-16T02:15:00Z",
    "mappingCoverage": 0.9962,
    "perCloud": [ { "cloud": "aws", "total": 812, "lastSuccessAt": "2026-11-16T02:15:00Z", "status": "OK" } ]
  }
}
```

### 3.6 `GET /inventory/gates`

```json
{
  "data": [
    { "site": "management", "gate": "mgmt_enabled", "declared": false, "actual": false,
      "resourcesPresent": true, "state": "CONSISTENT", "checkedAt": "2026-11-16T02:15:00Z" }
  ]
}
```

`state`: `CONSISTENT` / `GATE_MISMATCH` / `RESOURCES_LEAKED`（关闭但资源仍存在）。

---

## 4. 栈图谱 / Graph

### 4.1 `GET /graph`

| 参数 | 说明 |
| --- | --- |
| `site` | 可选，缺省返回全部站点 |
| `view` | `layer` / `flow` / `cost`，决定布局与可见边 |

响应：

```json
{
  "data": {
    "nodes": [
      { "id": "photos/02-data", "site": "photos", "layer": "02-data", "kind": "data",
        "layerNo": 3, "label": "02-data", "cloud": "aws",
        "resourceCount": 12,
        "cost": { "amount": 12.43, "currency": "USD", "basis": "AMORTIZED", "precision": "DETAIL" },
        "health": "OK",
        "layout": { "x": 40, "y": 118, "w": 150, "h": 60 } }
    ],
    "edges": [
      { "from": "photos/02-data", "to": "photos/03-compute",
        "provenance": "STACK_STRUCTURE", "weight": 1.0,
        "evidence": "层号 3 → 4，同 site 相邻，site.yaml 声明 depends_on",
        "hiddenByDefault": false, "broken": false }
    ],
    "legend": { "provenanceCounts": { "STACK_STRUCTURE": 6, "TF_REMOTE_STATE": 1, "RESOURCE_REF": 3, "CONFIG_REF": 2, "TAG_INFERRED": 4 } },
    "hiddenEdgeCount": 4,
    "health": { "isolated": 0, "broken": 1, "coveredLayers": 9 }
  }
}
```

### 4.2 `GET /graph/{site}/{layer}/impact`

```json
{
  "data": {
    "node": "photos/04-edge",
    "impact1": [ { "id": "photos/03-compute", "via": "STACK_STRUCTURE", "why": "层号 4 → 5，site.yaml 声明 depends_on" } ],
    "impact2": [ ],
    "impact3": [ ],
    "truncated": false
  }
}
```

`truncated = true` 表示超过 120 个节点未展开，前端显示「另有 N 个」并可请求 `?expand=true`。

---

## 5. 漂移 / Drift

### 5.1 `GET /drift/findings`

参数：`severity`、`state`、`site`、`layer`、`channel`、`class`、`resource`。

```json
{
  "data": [
    { "id": 9821, "channel": "A", "severity": "HIGH", "state": "OPEN",
      "class": null,
      "resource": { "cloud": "aws", "nativeId": "arn:aws:s3:::chenyouyi", "name": "chenyouyi",
                    "site": "photos", "layer": "01-storage" },
      "attributePath": "lifecycle_rules",
      "expected": [ { "id": "expire-tmp", "days": 7 } ],
      "actual": [],
      "firstSeenAt": "2026-11-14T02:10:00Z",
      "lastSeenAt": "2026-11-16T02:10:00Z",
      "occurrenceCount": 3,
      "likelyRunId": 4412,
      "reason": null }
  ],
  "meta": { "page": 0, "size": 50, "total": 27, "hasNext": false },
  "coverage": { "managed": 1834, "checked": 1802, "ratio": 0.9826 }
}
```

**`coverage` 必须与列表一起返回**（R-DRF-11），否则用户会误读"27 条就是全部问题"。

### 5.2 `POST /drift/findings/{id}/ack`

```json
{ "reason": "手工调整生命周期，代码将同步", "reviewBy": "2026-11-23T00:00:00Z", "version": 3 }
```

`reason` 长度 ≥ 8；`reviewBy` 必填且未来 30 天内。

### 5.3 `POST /drift/findings/{id}/ignore`

```json
{ "reason": "AWS 自动写入的系统标签", "expiresAt": "2027-02-01T00:00:00Z", "version": 3 }
```

同时会创建 `drift_ignore_rule`（可选 `createRule: true`，默认 true）。

### 5.4 `GET /drift/runs`

返回两通道最近 30 次运行，字段含 `channel / status / checkedCount / findingCount / error`。`status = INCONCLUSIVE` 时前端必须区分展示。

### 5.5 `GET /drift/coverage`

按 `(cloud, site, layer)` 返回 `managed / checked / ratio / staleBaseline`。

---

## 6. 成本 / Cost

### 6.1 `GET /cost/summary`

| 参数 | 说明 |
| --- | --- |
| `period` | `2026-10` 或 `2026-09..2026-11` |
| `basis` | `BILLED / AMORTIZED / ALLOCATED`，默认 `ALLOCATED` |
| `groupBy` | `site / layer / service / cloud / canonicalType`，可多值 |
| `site` | 过滤 |

```json
{
  "data": {
    "total": { "amount": 4213.55, "currency": "CNY", "basis": "ALLOCATED", "precision": "DETAIL" },
    "groups": [
      { "key": "photos/03-compute", "amount": { }, "share": 0.412,
        "deltaVsPrevPeriod": 0.083, "unknownRows": 0 }
    ],
    "unallocated": { "amount": 61.20, "currency": "CNY", "basis": "ALLOCATED", "precision": "DETAIL", "ratio": 0.0143 },
    "provisional": [ { "cloud": "gcp", "period": "2026-10", "reason": "导出延迟" } ]
  }
}
```

`unallocated` **总是**返回，即使为 0（前端要展示比例）。

### 6.2 `GET /cost/trend`

`granularity=day|month`，返回 `series: [{ key, points: [{t, amount}] }]`，最多 6 条 series（超过需聚合）。

### 6.3 `GET /cost/bridge`

```json
{
  "data": {
    "period": "2026-10",
    "billed": { "amount": 4521.10, "currency": "CNY", "basis": "BILLED", "precision": "DETAIL" },
    "allocated": { "amount": 4213.55, "currency": "CNY", "basis": "ALLOCATED", "precision": "DETAIL" },
    "items": [
      { "key": "shared_unallocated", "label": "未分摊共享成本", "amount": 168.40, "ratio": 0.547 },
      { "key": "commitment_unamort",  "label": "承诺折扣未摊销", "amount": 92.15,  "ratio": 0.300 },
      { "key": "cross_period_adj",    "label": "跨期调整",       "amount": 30.00,  "ratio": 0.098 },
      { "key": "tax",                 "label": "税费",           "amount": 12.00,  "ratio": 0.039 },
      { "key": "fx_diff",             "label": "汇率差",         "amount": 5.00,   "ratio": 0.016 }
    ],
    "reconciles": true
  }
}
```

`reconciles = (billed - allocated) == sum(items)`，服务端计算，为 `false` 时前端显示"差异未完全解释"。

### 6.4 `GET /cost/allocations`

按 stack 展开分摊明细，含 `step`（1..4）与 `ruleVersion`。

### 6.5 `GET /cost/savings`

每条含 `kind / estimatedSaving / formula / evidence / state`。`formula` 是可直接阅读的算式，如 `闲置 14 天 × 730h × $0.024/h`。

### 6.6 `POST /cost/rebuild`

```json
{ "period": "2026-10", "basis": "ALLOCATED", "ruleVersion": 1 }
```

返回 `202 { "jobId": "..." }`，进度走 `GET /platform/jobs`。

### 6.7 `GET /cost/export`

参数同 `summary`，另加 `format=csv|parquet`。返回 `302` 到 Blob 预签名 URL（5 分钟有效），文件名 `cost-{period}-{basis}-{ts}.csv`。CSV 头部注释写入查询参数与生成时间（R-FIN-13）。

---

## 7. 发布 / Delivery

### 7.1 `GET /delivery/runs`

参数：`repo`、`status`、`mode`、`from`、`to`、`site`、`layer`。

```json
{
  "data": [
    { "id": 4412, "repo": "scott372199/album-infra", "runId": 12841022931,
      "workflow": "infra", "event": "workflow_dispatch", "mode": "apply",
      "status": "completed", "conclusion": "success",
      "headSha": "3f9c1ab", "prNumber": null, "actor": "scott372199",
      "startedAt": "2026-11-15T09:12:00Z", "finishedAt": "2026-11-15T09:31:00Z",
      "riskLevel": "MEDIUM",
      "resourceChanges": { "added": 1, "changed": 4, "destroyed": 0, "replaced": 0 },
      "layers": ["photos/03-compute"],
      "url": "https://github.com/..." }
  ]
}
```

### 7.2 `GET /delivery/runs/{id}`

额外返回 `jobs[]`（含 site/layer/耗时）、`changes[]`（每项 `action / address / nativeId / before / after`）、`linkedDrifts[]`。

### 7.3 `POST /delivery/dispatch`

三步流程，三个端点：

| 步 | 端点 | 说明 |
| --- | --- | --- |
| 1 | `POST /delivery/dispatch/precheck` | body `{site, layers}` → 返回 `planSummary`、`destroyedCount`、`criticalDriftCount`、`riskLevel`、`allowed`（bool）、`blockers[]` |
| 2 | `POST /delivery/dispatch` | body `{site, layers, precheckToken, reason}` → 创建 `dispatch_request`，`state=PENDING` |
| 3 | `POST /delivery/dispatch/{id}/confirm` | 由**另一名** OWNER 调用，body `{precheckToken}` → `state=CONFIRMED` 并立即 dispatch |

约束（`02` R-DEL-05/06）：

- `precheck.allowed = false` 时第 2 步返回 `422 PRECONDITION_FAILED`，`blockers` 原样透出。
- `precheckToken` 有效期 10 分钟，绑定 `(site, layers, actor)`。
- 第 3 步若 `confirmedBy == requestedBy` 返回 `403 TWO_MAN_REQUIRED`。
- 24 小时未确认自动 `EXPIRED`。

`precheck` 响应示例：

```json
{
  "data": {
    "site": "photos",
    "layers": ["03-compute"],
    "planSummary": { "add": 1, "change": 4, "destroy": 0, "replace": 0 },
    "criticalDriftCount": 0,
    "openSecurityHigh": 2,
    "riskLevel": "MEDIUM",
    "allowed": true,
    "blockers": [],
    "precheckToken": "pc_7f3a…",
    "expiresAt": "2026-11-16T10:10:00Z"
  }
}
```

### 7.4 `GET /delivery/metrics`

返回 `successRate`、`runsPerWeek`、`leadTimeMinutes`（p50/p90）、`mttrMinutes`，每项带 `formula` 字符串（R-DEL-09）。

---

## 8. 观测 / Observability

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/observe/metrics?stack=&window=` | 每 stack ≤ 8 个关键指标，含 `baseline` 与 `sourceLink` |
| `GET` | `/observe/alerts?state=&severity=` | 聚合告警（30 分钟窗口合并） |
| `GET` | `/observe/links?stack=&kind=logs\|traces\|metrics` | 原生系统跳转链接（含时间范围与过滤） |
| `GET` | `/observe/slo` | SLO 与燃烧率 |

`metrics` 单项：

```json
{ "key": "lambda_errors", "label": "函数错误数", "unit": "count",
  "latest": 0, "baseline": { "yesterday": 0, "lastWeek": 1 },
  "series": [ { "t": "2026-11-16T01:00:00Z", "v": 0 } ],
  "source": "cloudwatch", "sourceLink": "https://…", "status": "OK" }
```

`status`: `OK / NO_DATA / FORBIDDEN / NOT_CONFIGURED`。`NO_DATA` 时必须带 `reason`。

---

## 9. 安全 / Security

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/security/findings` | 参数 `class`、`severity`、`state` |
| `GET` | `/security/findings/{id}` | 含 `evidence`、`recommendation`、`ruleVersion` |
| `POST` | `/security/findings/{id}/waive` | body `{reason, expiresAt, version}` |
| `GET` | `/security/summary` | 按 class × severity 的计数矩阵 |

密钥类结论的 `evidence` 只含 `{file, line, prefix4, suffix4}`，**永不含原文**（R-SEC-04）。

---

## 10. 通知 / Notification

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/notify/subscriptions` | 订阅列表 |
| `PUT` | `/notify/subscriptions/{id}` | 更新（需 `ELEVATED`） |
| `POST` | `/notify/test` | body `{channel, template}` → 发一条测试 |
| `GET` | `/notify/history?state=FAILED` | 发送历史 |

---

## 11. 平台 / Platform

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/platform/jobs` | 任务清单 + 最近一次运行 + 下次计划 |
| `POST` | `/platform/jobs/{name}/run` | 手动触发（`ELEVATED`，限流） |
| `GET` | `/platform/jobs/runs?job=&status=` | 运行历史 |
| `GET` | `/platform/events/dead` | 事件死信 |
| `POST` | `/platform/events/{id}/retry` | 重投 |
| `GET` `PUT` | `/platform/config` | 配置读写（写需 `ELEVATED`，产生新版本） |
| `GET` | `/platform/audit` | 审计查询 |
| `GET` | `/platform/dr/matrix` | 切换矩阵 |
| `POST` | `/platform/dr/drills` | 记录演练 |
| `GET` | `/platform/dr/drills` | 演练历史 |

### 11.1 `GET /platform/dr/matrix`

```json
{
  "data": [
    { "id": 1, "name": "图片站入口", "primary": "AWS CloudFront", "standby": "GCP Cloud CDN",
      "switchMethod": "dns_failover", "rtoMinutes": 15, "rpoMinutes": 5,
      "verified": true, "lastDrillAt": "2026-09-20T00:00:00Z",
      "staleDays": 57, "status": "OK",
      "preconditions": [ { "text": "Azure DNS 区可写", "verified": true },
                         { "text": "TTL 已到期（60s）", "verified": true } ] }
  ],
  "meta": { "unverifiedCount": 3, "staleCount": 1 }
}
```

`status`: `OK / STALE`（>90 天）/ `EXPIRED`（>180 天）/ `UNVERIFIED`。

### 11.2 `POST /platform/dr/drills`

```json
{ "conductedAt": "2026-11-16T02:00:00Z", "scope": "photos 全站 DNS 切换",
  "components": [1, 2], "actualRtoMinutes": 18, "actualRpoMinutes": 7,
  "outcome": "PARTIAL", "issues": "CloudFront 缓存未预热，首屏 4s",
  "deviation": "RTO 超出 3 分钟", "followups": ["预热脚本", "调整 RTO 到 20 分钟"] }
```

记录成功后服务端更新对应 `dr_component.last_drill_at`、`rto_minutes`（取实测值）、`verified = true`。

---

## 12. 事件流 / SSE

`GET /api/v1/stream`（`text/event-stream`），事件类型：

| event | 载荷 | 用途 |
| --- | --- | --- |
| `job.started` / `job.finished` | `{jobName, scopeKey, status}` | 采集进度条 |
| `drift.new` | `{findingId, severity, site, layer}` | 顶部铃铛 |
| `release.finished` | `{runId, conclusion, riskLevel}` | 发布状态 |
| `gate.changed` | `{site, gate, from, to}` | 横幅 |
| `degraded.changed` | `{source, active}` | 横幅 |

心跳 25 秒一次（`event: ping`）。断线由浏览器 `EventSource` 自动重连，服务端 5 分钟内的事件可重放（`Last-Event-ID`）。

---

## 13. 端点总表 / Endpoint index

| # | 方法 | 路径 | 角色 | 需二次确认 |
| --- | --- | --- | --- | --- |
| 1 | POST | `/auth/login` | — | — |
| 2 | POST | `/auth/logout` | 任意 | — |
| 3 | POST | `/auth/elevate` | OWNER | — |
| 4 | GET | `/auth/me` | 任意 | — |
| 5 | GET | `/inventory/resources` | 任意 | — |
| 6 | GET | `/inventory/resources/{cloud}/{nativeId}` | 任意 | — |
| 7 | GET | `/inventory/tree` | 任意 | — |
| 8 | GET | `/inventory/stacks` | 任意 | — |
| 9 | GET | `/inventory/health` | 任意 | — |
| 10 | GET | `/inventory/gates` | 任意 | — |
| 11 | GET | `/graph` | 任意 | — |
| 12 | GET | `/graph/{site}/{layer}/impact` | 任意 | — |
| 13 | GET | `/drift/findings` | 任意 | — |
| 14 | GET | `/drift/findings/{id}` | 任意 | — |
| 15 | POST | `/drift/findings/{id}/ack` | OWNER | — |
| 16 | POST | `/drift/findings/{id}/ignore` | OWNER | — |
| 17 | GET | `/drift/runs` | 任意 | — |
| 18 | GET | `/drift/coverage` | 任意 | — |
| 19 | GET | `/cost/summary` | 任意 | — |
| 20 | GET | `/cost/trend` | 任意 | — |
| 21 | GET | `/cost/bridge` | 任意 | — |
| 22 | GET | `/cost/allocations` | 任意 | — |
| 23 | GET | `/cost/savings` | 任意 | — |
| 24 | GET | `/cost/budgets` | 任意 | — |
| 25 | POST | `/cost/rebuild` | OWNER | ✔ |
| 26 | GET | `/cost/export` | OWNER | ✔ |
| 27 | GET | `/delivery/runs` | 任意 | — |
| 28 | GET | `/delivery/runs/{id}` | 任意 | — |
| 29 | GET | `/delivery/changes` | 任意 | — |
| 30 | POST | `/delivery/dispatch/precheck` | OWNER | ✔ |
| 31 | POST | `/delivery/dispatch` | OWNER | ✔ |
| 32 | POST | `/delivery/dispatch/{id}/confirm` | OWNER（他人） | ✔ |
| 33 | GET | `/delivery/metrics` | 任意 | — |
| 34 | GET | `/observe/metrics` | 任意 | — |
| 35 | GET | `/observe/alerts` | 任意 | — |
| 36 | GET | `/observe/links` | 任意 | — |
| 37 | GET | `/observe/slo` | 任意 | — |
| 38 | GET | `/security/findings` | 任意 | — |
| 39 | GET | `/security/findings/{id}` | 任意 | — |
| 40 | POST | `/security/findings/{id}/waive` | OWNER | — |
| 41 | GET | `/security/summary` | 任意 | — |
| 42 | GET | `/notify/subscriptions` | 任意 | — |
| 43 | PUT | `/notify/subscriptions/{id}` | OWNER | ✔ |
| 44 | POST | `/notify/test` | OWNER | — |
| 45 | GET | `/notify/history` | 任意 | — |
| 46 | GET | `/platform/jobs` | 任意 | — |
| 47 | POST | `/platform/jobs/{name}/run` | OWNER | ✔ |
| 48 | GET | `/platform/jobs/runs` | 任意 | — |
| 49 | GET | `/platform/events/dead` | 任意 | — |
| 50 | POST | `/platform/events/{id}/retry` | OWNER | — |
| 51 | GET | `/platform/config` | 任意 | — |
| 52 | PUT | `/platform/config` | OWNER | ✔ |
| 53 | GET | `/platform/audit` | OWNER | — |
| 54 | GET | `/platform/dr/matrix` | 任意 | — |
| 55 | GET | `/platform/dr/drills` | 任意 | — |
| 56 | POST | `/platform/dr/drills` | OWNER | — |
| 57 | GET | `/stream` | 任意 | — |

**57 个端点，覆盖 9 个页面全部数据需求。** 新增端点在 PR 中必须同时在 `05` 的页面数据来源表登记。

---

## 14. 契约测试 / Contract tests

| 测试 | 内容 |
| --- | --- |
| `OpenApiSchemaTest` | 启动时生成 OpenAPI 3.1，与 `openapi.json` 快照比对，破坏性变更失败 |
| `MoneyShapeTest` | 所有返回金额的端点，序列化必须含 `amount/currency/basis/precision` 四键 |
| `DegradedTest` | 模拟每个数据源失败，端点返回 200 + `degraded[]`，不返回 5xx |
| `TwoManRuleTest` | 同一用户 confirm 返回 403 |
| `RoleMatrixTest` | 遍历端点表，VIEWER 对写端点全部 403 |
