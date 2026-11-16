# 01 · 总体设计 / Overall Design

| 项 / Item | 值 / Value |
| --- | --- |
| 状态 / Status | Approved for implementation |
| 依赖 / Depends on | `README.md` §3（仓库对接点） |
| 被引用 / Referenced by | `02`、`03`、`05`、`06` |

---

## 1. 目标与成功标准 / Goals and success criteria

平台要解决的是一个具体问题：**资源、成本、变更、风险分散在三个云和一个 CI 系统里，任何一个问题都要切换四个控制台才能回答。**

*The problem is concrete: resources, cost, change and risk are scattered across three clouds and one CI system; any single question costs four consoles.*

### 1.1 四条成功标准（可测）/ Four measurable criteria

| # | 标准 / Criterion | 判定方式 / How measured |
| --- | --- | --- |
| SC-1 | 对任意一个资源，**三次点击内**回答：属于哪个项目/层、被谁依赖、上月花了多少、最近一次变更来自哪个 PR | 人工走查 10 个随机资源，全部命中 |
| SC-2 | 月度成本 **T+2 内**可查，三口径差异桥把未分配金额压到 **< 2%** | 连续 3 个月账期校验 |
| SC-3 | 漂移检测覆盖 **100% 受管资源**，日频通道**误报率 < 5%** | 抽样 50 条漂移，人工确认 |
| SC-4 | 任一次 apply run，**5 分钟内**在平台看到它改了哪些资源 | 对比 GitHub Actions 与平台时间戳 |

四条不达标即视为该阶段未完成。**没有第五条"体验良好"之类的软指标。**

### 1.2 反面目标 / Anti-goals

平台做得再好也不改变一件事：**它不拥有基础设施。** 代码与 CI 拥有基础设施。平台是一个**有记忆、能解释的镜像**。

*The platform never owns infrastructure. Code and CI do. The platform is a mirror with memory and explanations.*

---

## 2. 范围 / Scope

| 阶段 / Phase | 范围 / Scope |
| --- | --- |
| **S0 骨架** | 单进程可跑、Flyway 建表、静态资源清单导入、栈图谱只读 |
| **S1 目录与漂移** | 三云资源采集、栈图谱全量、漂移双通道 A |
| **S2 成本** | CUR / GCP Billing / Azure Cost 接入、FOCUS 归一、三口径、分摊 |
| **S3 治理** | 漂移通道 B、发布链路、安全姿态、事件与通知 |
| **S4 容灾** | 切换矩阵、演练记录、容量与风险看板 |

范围之外见 `README.md` §2.2。**每个阶段都能独立上线**，不存在"必须全部做完才有价值"的设计。

---

## 3. 约束 / Constraints

这些数字是设计的输入，不是估计。**任何超出约束的假设都必须在实现前改回来。**

| 约束 / Constraint | 值 / Value | 对设计的影响 / Impact |
| --- | --- | --- |
| AWS 账号数 | < 10 | 不需要 Organization 级聚合器，单账号循环即可 |
| 受管资源总数 | < 2,000 | 全量快照进 PostgreSQL 完全可行，不需要图数据库 |
| Terraform scope | 16 个（`s00-account` + 3 站 × 5 层） | 每 scope 独立 state，采集按 scope 并行 |
| 团队规模 | 1–3 人 | 模块化单体；一个人要能同时改前后端 |
| 运行时 | Java 21 / Spring Boot 3.3 / PostgreSQL 16 / React 18 | 见 §9 |
| 账单管道 | **尚未建立**：CUR 未导出、GCP Billing Export 未配、Azure 无导出 | 成本能力必须能"从零起步"，见 `02` §4.3 |
| 开发机 | macOS，无 K8s，无本地云凭据 | `docker compose` 起 PG，采集器支持 `--fixture` 离线模式 |
| 平台自身月成本 | < ¥800 | 单实例 + 单 PG + Blob；不引入 Redis / MQ / ES |
| 网络 | 无固定公网 IP，无 VPN | 平台只出站；入站走 Cloudflare/Azure Front Door 之外的反向代理或仅内网 |

---

## 4. 设计原则 / Principles

八条。每条都能在代码里找到落点，找不到就是空话。

| # | 原则 / Principle | 落点 / Where it shows |
| --- | --- | --- |
| P1 | **代码是唯一真相，平台只读** | 平台无任何云写权限；IAM 策略里没有任何 `Create*` / `Delete*` / `Put*` |
| P2 | **一个进程，边界靠模块不靠服务** | Spring Modulith `@ApplicationModule(allowedDependencies=…)` + `ApplicationModules.verify()` 编译期强制 |
| P3 | **事件落库，不用 Kafka** | Modulith Event Publication Registry，`event_publication` 表，可重放、有 outbox 语义 |
| P4 | **只读采集走各云聚合清单 API** | AWS Resource Explorer / GCP Cloud Asset Inventory / Azure Resource Graph；不自己爬分页 |
| P5 | **先归一再分摊** | 任何分摊计算之前，成本必须已经是 FOCUS 行；未归一的原始行只进 `raw_*` |
| P6 | **拓扑来自结构，不来自猜测** | 边权重：栈结构 / remote_state 1.0 > 引用 0.95 > 配置 0.90 > 流量 0.85 > 标签 0.70 |
| P7 | **一切结论可点开看证据** | 每个指标卡、每条漂移、每个成本数字都能点到原始行或原始 API 响应 |
| P8 | **无数据就说未知，不说 0** | 前端 `null` 渲染为「未知」，不参与求和；后端 `Decimal` 空值不写 0 |

P8 容易被违反。规定：**所有金额字段在 DDL 中可为空**，前端求和时显式跳过 `null` 并展示覆盖率（如「12/15 层已出账」）。

---

## 5. 总体架构 / Overall architecture

![分层架构](diagrams/01-layered-architecture.svg)

七层，自上而下：

| 层 / Layer | 内容 / Contents | 技术 / Tech |
| --- | --- | --- |
| L0 交互 | 9 个页面、图表、跳转 | React 18 + Vite + 手写 SVG |
| L1 接口 | REST `/api/v1`、SSE 事件流 | Spring MVC + `SseEmitter` |
| L2 领域 | INV / DRIFT / FIN / DEL / OBS / SEC / NOT | Spring Modulith 模块 |
| L3 编排 | 采集任务调度、任务状态机、重试 | Spring `@Scheduled` + ShedLock |
| L4 采集 | 三云 SDK 客户端、GitHub 客户端、快照读取 | AWS SDK v2 / GCP Java SDK / Azure SDK |
| L5 存储 | PostgreSQL 16（事实与派生）、Azure Blob（原始快照、导出） | Flyway / JPA / DuckDB（分析查询） |
| L6 运行 | Azure Container Apps、Key Vault、Log Analytics | Terraform `sites/platform` |

### 5.1 数据流向 / Data flow

```
云 API / GitHub / CI 快照
        │  (只读)
        ▼
   L4 采集器  ──原始响应──▶  Blob: raw/{source}/{date}/*.json      ← 证据留存，7 天
        │
        ▼ 归一
  PostgreSQL: resource / cost_fact / change_event / drift_finding   ← 事实表，只追加
        │
        ▼ 派生（定时物化）
  PostgreSQL: stack_edge / allocation / kpi_snapshot                ← 派生表，可重建
        │
        ▼
   L1 REST / SSE  ──▶  L0 页面
```

三条硬规则：

| # | 规则 | 理由 |
| --- | --- | --- |
| D1 | 事实表**只追加**，不 UPDATE 覆盖；修正靠新行 + `superseded_by` | 任何历史结论都可复现 |
| D2 | 派生表**必须可从事实表全量重建**，重建命令是一个 API/CLI | 派生逻辑改了不用洗数据 |
| D3 | 原始响应落 Blob 保留 **7 天**，超过即删 | 平台故障时可从原文重放 |

### 5.2 三平面 / Three planes

![三平面与容灾](diagrams/08-multi-plane-dr.svg)

| 平面 / Plane | 承载 / Hosts | 云 / Cloud | 职责 / Role |
| --- | --- | --- | --- |
| **DATA** | 业务站点：photos、GCP 备用栈 | AWS（主）+ GCP（备/双活） | 跑业务流量 |
| **CONTROL** | 平台本体、PG、Blob、Key Vault、权威 DNS | Azure | 决策所需的事实与凭据 |
| **OBS** | Grafana / Loki / Prometheus / Tempo | AWS `sites/management`（门控） | 观测数据源，平台只读聚合 |

**为什么 CONTROL 在 Azure**：平台必须在 AWS 出故障时仍然可用——否则"恢复 AWS"这件事本身没有指挥台。把平台、平台 state、平台凭据放在与数据面不同的云上，是这个设计的**唯一**目的。

*CONTROL lives on Azure for exactly one reason: the platform must survive an AWS incident, or nobody can command the recovery.*

`OBS` 留在 AWS 是**接受的缺口**：AWS 挂了，AWS 的指标本来就没了。补偿手段是 GCP 侧独立采集 + 平台保留 24 小时指标快照，够判断"是 AWS 整体故障还是单点"。

### 5.3 跨云入口 / Cross-cloud entry

- 权威 DNS 在 **Azure DNS**（`chenyouyi.top`），TTL 分层：静态资源 3600s、API 60s、切换记录 60s。
- 切换方式：DNS 层 failover（方案 A）。**不引入 Front Door / Traffic Manager 做全局入口**——那会让 Azure 变成数据面组件，全站可用性压在单一产品上。
- 平台**不改 DNS**：只展示当前解析、切换步骤与上次演练时间。

### 5.4 平台在容灾中的边界 / Platform's role in DR

| 做 / Does | 不做 / Does not |
| --- | --- |
| 展示切换矩阵与 RTO/RPO | 执行切换 |
| 提供逐步 runbook 与检查项 | 改 DNS 记录 |
| 触发预置的 GitHub Actions（双人确认） | 持有提升权限 |
| 记录演练结果与偏差 | 首期自动切换 |

---

## 6. 应用架构 / Application architecture

![Modulith 模块](diagrams/02-modulith-modules.svg)

### 6.1 模块与依赖方向 / Modules and dependency direction

```
                    ┌──────────────┐
                    │   platform   │  ← 横切：身份、任务、审计、配置
                    └──────┬───────┘
        ┌──────────┬───────┼────────┬──────────┬─────────┐
        ▼          ▼       ▼        ▼          ▼         ▼
   inventory    drift   finops   delivery   observe   security
        │          │       │        │          │         │
        └──────────┴───────┴────────┴──────────┴─────────┘
                             ▼
                         notify
```

`@ApplicationModule` 声明（**这一段是编译期契约，不是文档**）：

```java
@ApplicationModule(allowedDependencies = {"platform", "notify"})
package com.album.console.inventory;

@ApplicationModule(allowedDependencies = {"platform", "inventory", "notify"})
package com.album.console.drift;

@ApplicationModule(allowedDependencies = {"platform", "inventory", "notify"})
package com.album.console.finops;

@ApplicationModule(allowedDependencies = {"platform", "inventory", "delivery", "notify"})
package com.album.console.observe;

@ApplicationModule(allowedDependencies = {"platform", "inventory", "security", "notify"})
package com.album.console.delivery;   // 读 security 的暴露面结论，用于发布风险评级

@ApplicationModule(allowedDependencies = {"platform", "inventory", "notify"})
package com.album.console.security;

@ApplicationModule(allowedDependencies = {"platform"})
package com.album.console.notify;

@ApplicationModule(allowedDependencies = {})
package com.album.console.platform;
```

三条不可协商：

| # | 规则 | 强制方式 |
| --- | --- | --- |
| M1 | `inventory` **不依赖**任何业务模块，它是唯一的资源真源 | `verify()` 失败即构建失败 |
| M2 | 模块间**只能**通过 `@NamedInterface` 暴露的接口或事件通信，不得直接注入对方 Repository | ArchUnit 规则 `noClasses().that().resideInAPackage("..internal..").should().beAccessedByClassesThat()…` |
| M3 | 跨模块的读操作允许同步调用接口；跨模块的**写**只能靠事件 | 代码评审 + `event_publication` 覆盖检查 |

### 6.2 事件目录 / Event catalogue

事件名即表内 `event_type`，全部经 Event Publication Registry 持久化。

| 事件 / Event | 发布方 | 订阅方 | 触发时机 |
| --- | --- | --- | --- |
| `ResourceSnapshotCompleted` | inventory | drift, finops, notify | 一次采集完成，含新增/消失/变更计数 |
| `ResourceDiscovered` | inventory | drift, security | 单个新资源首次入库 |
| `ResourceVanished` | inventory | drift, notify | 资源在上游消失（区别于"被删除"） |
| `DriftDetected` | drift | notify, delivery | 单条漂移成立 |
| `DriftResolved` | drift | notify | 漂移消失 |
| `CostPeriodClosed` | finops | notify | 某账期成本采集完成且校验通过 |
| `AllocationRebuilt` | finops | notify | 分摊重建完成 |
| `WorkflowRunObserved` | delivery | notify, drift | 捕获到一次 plan/apply run |
| `GateStateChanged` | inventory | notify, observe | `mgmt_enabled` 等受管开关变化 |
| `SecurityFindingRaised` | security | notify, delivery | 新安全结论成立 |
| `DrillRecorded` | platform | notify | 演练记录落库 |

事件订阅失败必须**不阻塞**发布方：`@ApplicationModuleListener` 默认在独立事务中执行，失败进 `event_publication` 的 `completion_date IS NULL` 集合，由 `platform` 模块的补偿任务每 5 分钟重试，超过 5 次进死信视图（`/api/v1/platform/events/dead`）。

### 6.3 任务编排 / Job orchestration

所有采集任务满足：

| # | 规则 |
| --- | --- |
| J1 | 同一 `(source, scope)` 的任务**串行**，用 ShedLock 表锁实现，锁超时 = 任务超时 × 2 |
| J2 | 每个任务有 `job_run` 行：`started_at / finished_at / status / rows_in / rows_out / error` |
| J3 | 任务失败**不重试超过 3 次**，且退避为 1m / 5m / 15m |
| J4 | 任务有硬超时：资源采集 10 分钟，成本采集 30 分钟，plan 快照解析 10 分钟 |
| J5 | 采集失败时页面显示**上次成功时间**，不显示"最新" |

---

## 7. 多云统一模型 / Unified multi-cloud model

![多云统一模型](diagrams/06-multicloud-model.svg)

三云的差异集中在四个维度。平台只在四个地方做归一，**其它地方一律保留云的原始字段**。

### 7.1 四个归一点 / Four normalization points

| # | 维度 / Dimension | 归一方式 / Normalization |
| --- | --- | --- |
| N1 | **资源域** | `scope_node` LTREE 树：`cloud → account/subscription → site → layer`，可任意深度扩展 |
| N2 | **资源类型** | `canonical_type`（如 `storage.object`），与云内类型（`AWS::S3::Bucket`）多对一映射，映射表可查可改 |
| N3 | **标签语义** | 规定 6 个规范键，各云标签名映射到规范键；**键名比较统一转小写** |
| N4 | **成本** | FOCUS 1.x 行模型，`charge_period_start` 统一为 UTC 月初 |

### 7.2 scope_node 树 / The scope tree

```
cloud:aws
└── account:156487532333
    ├── site:photos
    │   ├── layer:01_storage
    │   ├── layer:02_data
    │   ├── layer:03_compute
    │   └── layer:04_edge
    └── site:management
        └── layer:03_compute
cloud:gcp
└── project:album-backup
    └── site:photos-dr
cloud:azure
└── subscription:<sub-id>
    ├── rg:platform-prod
    │   ├── layer:02_data
    │   └── layer:03_compute
    └── rg:platform-shared
```

约束：

| # | 规则 |
| --- | --- |
| S1 | 树深度不设上限，但**任何资源必须能挂到叶子**；挂不上的进 `scope_node:unassigned`，并计入分母 |
| S2 | Azure 多一层 Resource Group，映射规则：`subscription/rg` 两级都建节点，`layer` 挂到 `rg` 下 |
| S3 | 节点路径变更（改名/移动）走 `scope_node_alias` 记录旧路径，历史数据按旧路径仍可查 |
| S4 | `scope_node` 的 `kind` 只能是 `cloud / account / subscription / project / site / rg / layer / unassigned` |

### 7.3 六个规范标签 / Six canonical labels

| 规范键 / Key | 必填 | 用途 | 缺失后果 |
| --- | --- | --- | --- |
| `app` | 是 | 归属应用，成本分摊第一维 | 归入 `unallocated` |
| `site` | 是 | 对应 `site.yaml` 的 site | 由 scope 推断，推断值标 `inferred=true` |
| `layer` | 是 | 对应层 | 同上 |
| `env` | 是 | `prod` / `staging` / `dev` | 归 `prod`，并记一条 `security_finding` |
| `owner` | 否 | 责任人 | 不影响分摊，影响通知路由 |
| `cost-center` | 否 | 成本中心 | 无则用 `app` 兜底 |

**三云硬差异与处理**（写死在采集器的 `TagNormalizer`）：

| 差异 / Difference | 处理 / Handling |
| --- | --- |
| GCP 标签键强制小写，且不允许 `-` | 规范键用 `-`（`cost-center`）→ GCP 侧写 `cost_center`；读取时两者都映射到同一规范键 |
| Azure tag key 大小写不敏感但保留原样 | 比较一律 `toLowerCase()`；**展示保留云端原始写法**，便于对照 |
| Azure 单资源最多 50 个 tag，AWS 50，GCP 64 | 规范标签只占 6 个，留足余量；采集到超限时不报错，记 `tag_budget_warning` |
| GCP 资源名全球唯一、AWS ARN 分区化、Azure 资源 ID 含大小写混合 | 平台内部主键用 `(cloud, native_id)` 复合，**不解析** ID 结构 |

### 7.4 canonical_type 映射 / Type mapping

首批覆盖 42 个类型，分 6 组（`storage / data / compute / edge / identity / observe`）。映射表是一张数据表 `type_mapping`，不是枚举：

```sql
-- 例：三云的“对象存储”都映射到 storage.object
('aws',   'AWS::S3::Bucket',              'storage.object'),
('gcp',   'storage.googleapis.com/Bucket','storage.object'),
('azure', 'Microsoft.Storage/storageAccounts', 'storage.object')
```

未映射的资源进 `canonical_type = 'unknown'`，并在「目录健康度」里以**数量 + 涉及层**展示。**不做猜测性映射。**

### 7.5 区域 metro 归一 / Region metro

跨云比较延迟与成本时，用 metro 而不是 region 名。

| metro | AWS | GCP | Azure |
| --- | --- | --- | --- |
| `singapore` | `ap-southeast-1` | `asia-southeast1` | `southeastasia` |
| `tokyo` | `ap-northeast-1` | `asia-northeast1` | `japaneast` |
| `virginia` | `us-east-1` | `us-east4` | `eastus` |
| `frankfurt` | `eu-central-1` | `europe-west3` | `germanywestcentral` |

### 7.6 能力矩阵 / Capability matrix

**不假设三云等价。** 平台对每个跨云能力显式标注可用性，前端在不可用处显示「此云不支持」而不是空。

| 能力 / Capability | AWS | GCP | Azure |
| --- | --- | --- | --- |
| 聚合资源清单 | Resource Explorer（需开启） | Cloud Asset Inventory | Resource Graph |
| 成本明细导出 | CUR 2.0 → S3 | Billing Export → BQ | Cost Management Export |
| 标签级成本 | 支持（含成本分配标签） | 支持（label keys） | 支持（tag inheritance 需注意） |
| 变更历史 | CloudTrail | Cloud Audit Logs | Activity Log |
| `terraform plan` 快照 | CI 侧生成 | CI 侧生成 | CI 侧生成 |
| 预留/承诺折扣归一 | Savings Plans / RI | CUD | Reservations |

矩阵的用途不是"比较谁强"，而是**采集器的分支依据**：能力缺失时走降级路径，并把降级状态显示在数据源健康页。

---

## 8. 栈图谱 / Stack graph

![栈图谱三视图](diagrams/07-service-graph.svg)

### 8.1 节点定义 / Nodes

**节点不是"服务"，是 `stack = (site, layer)`。** 理由：`site.yaml` + 目录结构已经是一份精确的拓扑声明，不需要再发明一层抽象。

```java
record Stack(String site, String layerId, LayerKind kind, int layerNo,
             String cloud, String scopePath, BigDecimal monthlyCost,
             Health health) {}
```

首批节点（16 个 = `s00-account` + 3 站 × 5 层）：

| site | layer | kind | layer no | 主要内容 |
| --- | --- | --- | --- | --- |
| account | s00-account | account | 0 | state 容器、跨云 OIDC、infra 角色、KMS |
| photos | 00-bootstrap | bootstrap | 1 | 发布角色、镜像仓库 |
| photos | 01-storage | storage | 2 | 站点桶、源桶、下载桶 |
| photos | 02-data | data | 3 | 会话表、任务队列、参数 |
| photos | 03-compute | compute | 4 | 函数×2、HTTP API、负载均衡 |
| photos | 04-edge | edge | 5 | CDN、OAC、`/apk/*` 分流 |
| management | 00-bootstrap | bootstrap | 1 | 管理站镜像仓库 |
| management | 01-storage | storage | 2 | 持久数据桶、观测配置 |
| management | 02-data | data | 3 | 观测元数据（按需） |
| management | 03-compute | compute | 4 | 容器服务、观测栈 |
| management | 04-edge | edge | 5 | CDN `cx.` |
| platform | 00-bootstrap | bootstrap | 1 | 平台托管身份、Key Vault |
| platform | 01-storage | storage | 2 | Blob：快照、导出 |
| platform | 02-data | data | 3 | PostgreSQL 16 |
| platform | 03-compute | compute | 4 | Container App |
| platform | 04-edge | edge | 5 | 自定义域、证书 |

> `site = account` 对应仓库根目录的 `s00-account/`。它不参与任何站点的销毁，但与其他节点同构：一个 site、一个层、一份 state。

### 8.2 边的六级来源 / Edge provenance, six levels

**边的来源决定它的可信度，可信度必须显示在页面上。** 用户看到一个连线要能知道它是"代码写死的"还是"标签猜出来的"。

| 级别 / Level | 来源 / Source | 权重 | 说明 | 示例 |
| --- | --- | --- | --- | --- |
| 1 | **栈结构** | 1.00 | 层号相邻且同 site，结构上必然存在依赖 | photos `01-storage → 02-data` |
| 2 | **remote_state** | 1.00 | 代码里 `data "terraform_remote_state"` 显式引用 | `04-edge` 读 `03-compute` 的 ingress |
| 3 | **资源引用** | 0.95 | 一方配置里出现另一方的 ID/ARN/域名 | CloudFront origin 指向 ALB 域名 |
| 4 | **配置引用** | 0.90 | 环境变量、参数路径、桶名出现在对方配置 | Lambda env 里的 `TABLE_NAME` |
| 5 | **流量** | 0.85 | 观测到实际调用（VPC Flow / 访问日志），一期只对 CDN→origin 启用 | — |
| 6 | **标签推断** | 0.70 | 仅 `app` + `env` 相同，无其他证据 | **默认折叠**，需手动展开 |

规则：

| # | 规则 |
| --- | --- |
| G1 | 同一对节点存在多条证据时，取**最高级别**，其余作为 `evidence[]` 保留 |
| G2 | 级别 6 的边默认不画，页面上以「+N 条推断关系」提示，点击展开 |
| G3 | 每个边必须有 `evidence` 文本，无证据的边不入库 |
| G4 | 边的方向固定为「被依赖 → 依赖」（数据/控制流方向），不画反向边 |

### 8.3 三个视图 / Three views

| 视图 | 回答的问题 | 布局 | 数据来源 |
| --- | --- | --- | --- |
| **分层视图** | 这个站点怎么搭起来的 | 按层号横向分带，同带内并列 | 目录结构 + 边 |
| **数据流视图** | 一个请求经过哪些层 | 从左（入口）到右（存储），跨 site 汇聚 | 级别 2–5 的边 |
| **成本视图** | 钱花在哪个环上 | 节点面积 ∝ 月成本，颜色 ∝ 成本变化率 | `allocation` |

### 8.4 层类型适配度 / Layer-type fit

层的 `kind` 是**声明**，资源的 `canonical_type` 是**事实**。两者不一致时只标记不阻断——例如有人在 `03-compute` 里顺手放了一个队列（属于 data 类），平台记一条 `fit = 0`，作为下一次拆层的信号。

平台计算 `fit = 1` 或 `0`：

| layer kind | 允许的 canonical_type 组 |
| --- | --- |
| bootstrap | identity, storage.state, registry |
| storage | storage.* |
| data | data.* |
| compute | compute.*, container.*, serverless.*, api.*, data.* |
| edge | edge.* |
| observe | observe.* |

`fit = 0` 的资源进「层内异类资源」清单，**不报错、不阻断**，只作为重构信号。这是平台对现有仓库的真实反映，不是苛责。

---

## 9. 技术栈 / Stack

| 层 / Layer | 选型 / Choice | 版本 | 理由（一句） |
| --- | --- | --- | --- |
| 语言 | Java | 21 (LTS) | 虚拟线程让采集并发写起来简单 |
| 框架 | Spring Boot | 3.3.x | Modulith 与 Event Registry 开箱可用 |
| 模块 | Spring Modulith | 1.2.x | 编译期边界，不用拆服务 |
| 持久化 | PostgreSQL | 16 | LTREE + JSONB + 窗口函数，一套搞定 |
| 迁移 | Flyway | 10.x | 纯 SQL，可评审 |
| 调度 | Spring `@Scheduled` + ShedLock | 5.x | 单实例部署，DB 锁足够 |
| 缓存 | Caffeine | 3.x | 进程内，无额外组件 |
| 分析查询 | DuckDB (embedded) | 1.x | 直接查 Blob 上的 Parquet 成本明细，不引 Athena |
| 前端 | React | 18 | — |
| 构建 | Vite | 5.x | — |
| 图表 | 手写 SVG + 少量 D3 scale | — | 图谱与矩阵都是定制图形，图表库反而是负担 |
| 云 SDK | AWS SDK v2 / GCP Java SDK / Azure SDK | 最新稳定 | 只读调用 |
| 容器 | Azure Container Apps | — | 免运维，按需缩到 0 |
| 密钥 | Azure Key Vault | — | 与 CONTROL 平面同域 |

**不引入**：Kafka、Redis、Elasticsearch、Kubernetes、GraphQL、微服务框架、Athena、dbt。每一项都要有"为什么这里非它不可"的理由，目前没有。

---

## 10. 非功能指标 / Non-functional requirements

| 项 / Item | 目标 / Target | 验证方式 |
| --- | --- | --- |
| 目录页响应 | P95 < 400 ms（2,000 资源，默认筛选） | 压测脚本，`k6`，200 并发 |
| 图谱页首屏 | < 800 ms（≤ 120 节点 / 200 边） | Lighthouse + 服务端计时 |
| 成本查询 | P95 < 1.2 s（12 个月 × 单项目） | 同上 |
| 资源快照频率 | 15 分钟 | `job_run` 时间戳 |
| 成本采集频率 | 日频，T+1 数据；账期关闭后 T+2 校验 | 同上 |
| 漂移日频通道 | 每日 02:00 本地时区 | 同上 |
| 漂移周频通道 | 每周日 03:00，`plan -refresh-only` 由 CI 产出 | workflow 记录 |
| 数据保留 | 成本明细 36 个月；资源快照 90 天；原始响应 7 天 | 分区删除任务 |
| 存储估算 | 3 年成本明细 < 5 GB；资源快照 < 2 GB | 上线 3 个月后复核 |
| 平台可用性 | 99.0% / 月 | 外部探针 `/health` |
| 平台 RTO / RPO | 4 h / 24 h | 每半年演练一次 |
| 降级模式 | 采集失败时页面必须仍可读上次快照，并显式标注数据时间 | 断网演练 |
| 审计 | 所有触发类操作（dispatch workflow、导出）留痕，保留 12 个月 | `audit_log` |

---

## 11. 部署拓扑 / Deployment topology

### 11.1 全仓库的层结构（目标态）

| 站点 | 层 | kind | state key（Azure Blob） |
| --- | --- | --- | --- |
| `s00-account` | — | account | `account/base.tfstate` |
| `sites/photos` | `00-bootstrap` … `04-edge` | 五层同构 | `photos/{layer}.tfstate` |
| `sites/management` | `00-bootstrap` … `04-edge` | 五层同构 | `management/{layer}.tfstate` |
| `sites/platform` | `00-bootstrap` … `04-edge` | 五层同构 | `platform/{layer}.tfstate` |

**层号即 apply 顺序**，三站完全同构，站点之间可并行、站点之内串行。

### 11.2 平台自身的五层

| 层 | 内容 | 出口判据 |
| --- | --- | --- |
| `00-bootstrap` | 托管身份、Key Vault、镜像仓库、日志工作区 | `az keyvault show` 可见，身份可被 Container App 引用 |
| `01-storage` | Blob 容器：`raw/`、`snapshot/`、`export/` | 三个容器存在，生命周期策略生效 |
| `02-data` | PostgreSQL 16 Flexible Server、防火墙、备份 | `psql` 可连，Flyway 迁移成功 |
| `03-compute` | Container App（API + 静态前端）、探针、环境变量 | `/health` 返回 200 |
| `04-edge` | 自定义域、托管证书、访问限制 | 域名解析正确，证书有效 |

### 11.3 state 与引导 / State and bootstrap

全部 16 个 scope 的 state 都在 **Azure Blob**（容器 `tfstate`，lease 锁）。引导顺序：

1. 手工创建 storage account 与容器（一次性，`az` 命令，见 `08` E0）。
2. `s00-account` 首次 apply 用**本地 state**（此时它自己还不存在），apply 成功后 `terraform state push` 进 Blob，再启用后端。
3. 其余 15 个 scope 直接挂 Blob 后端，空 state 起步。

平台**不直连任何 state 容器**——它消费 CI 产出的 `state.json` / `plan.json` 快照（见 §12 I3）。

## 12. 与既有体系的集成点 / Integration points

| # | 集成 / Integration | 方式 / Mechanism | 谁发起 |
| --- | --- | --- | --- |
| I1 | 资源清单 | 三云聚合清单 API，只读角色，15 分钟一次 | 平台采集器 |
| I2 | 成本数据 | CUR 2.0（S3 Parquet）、GCP Billing Export（BQ）、Azure Cost Export（Blob）→ 平台侧 DuckDB 读 Parquet | 平台采集器 |
| I3 | IaC 快照 | CI 新增 `iac-snapshot.yml`（与既有 `cicd-snapshot.yml` 并列）：`terraform show -json` + `plan -out` 解析 → 写 Blob `snapshot/{site}/{layer}/{ts}.json` | GitHub Actions |
| I4 | 发布事件 | 读既有 `cicd-snapshot.yml` 产出的 `cicd/workflows.json`（平台不持有 GitHub 令牌做常规采集）；缺失的 jobs 明细按需用只读令牌补取 | 平台采集器 |
| I5 | 触发发布 | `POST /repos/{owner}/{repo}/actions/workflows/infra.yml/dispatches`，`mode=apply` + `confirm=apply`，受 `platform` 模块双人确认约束 | 平台（用户点击） |
| I6 | 通知 | 飞书自定义机器人 webhook（卡片）+ SMTP 兜底 | `notify` 模块 |
| I7 | 受管开关 | 读 `site.yaml` 的 `gate` 与 tfvars 中的实际值，二者不一致时报警 | `inventory` |

I3 是关键：**平台不解析 Terraform state 的原始格式**。`terraform show -json` 的输出是稳定契约，state 的存储格式不是。

I5 的凭据是一条 **fine-grained PAT**，只对 `album-infra` 仓库有 `Actions: write`，存 Key Vault，不允许 `contents: write`。平台**不能改代码**。

---

## 13. 未覆盖与风险 / Gaps and risks

| # | 风险 | 影响 | 缓解 |
| --- | --- | --- | --- |
| R1 | 三云账单管道尚未建立，S2 前半段无数据 | 成本页空转 | S1 先用 Cost Explorer / Billing API 的**月度汇总**做占位，明确标注"粗粒度"；S2 再换明细 |
| R2 | Resource Explorer 未开启 / 未建索引 | AWS 侧清单不全 | S1 前置动作：开启并等索引；缺失时降级为按区域 describe 循环 |
| R3 | 单人团队，平台自身也会腐烂 | 半年后无人维护 | 每个模块的验收断言进 CI；`make check` 覆盖 spec 中所有"必须"条目 |
| R4 | 平台计算的分摊与财务口径不一致 | 数字被质疑后失去信任 | 差异桥强制展示，未分配金额必须可见；分摊规则版本化，历史可按旧版本复现 |
| R5 | 漂移通道 B 需要 `plan -refresh-only`，可能触达真实 API 限流 | 周频任务失败 | 限制并发为 1，按 site 串行；失败不重试超过 1 次，下次周期再试 |
| R6 | CONTROL 平面在 Azure，团队不熟 Azure | 运维成本 | 平台自身层数控制在 5 层，全部用官方模块；不引入 Azure 特有 PaaS 的深水区 |

---

## 14. 追溯 / Traceability

| 本文章节 | 对应需求编号 | 验收位置 |
| --- | --- | --- |
| §1 成功标准 | SC-1..SC-4 | `06` §2 |
| §5.2 三平面 | FR-PLT-01 | `06` §2 S0 |
| §6.1 模块边界 | FR-PLT-02 | `06` §2 S0 |
| §7 多云模型 | FR-INV-01..04 | `06` §2 S1 |
| §8 栈图谱 | FR-INV-05..09 | `06` §2 S1 |
| §10 非功能 | FR-PLT-03 | `06` §2 S4 |
| §12 集成点 | FR-DEL-01、FR-INV-10 | `06` §2 S1/S3 |
