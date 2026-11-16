# album-infra / spec / V2 · 基础设施管理平台规格
# Infrastructure Management Platform — Specification

| 项 / Item | 值 / Value |
| --- | --- |
| 版本 / Version | **V2.0**（首个可开发版本 / first implementable version） |
| 状态 / Status | **可进入开发 / Approved for implementation** |
| 最后更新 / Updated | 2026-11-16 |
| 面向 / Audience | 负责实现平台的研发团队 / the team implementing the platform |
| 上游约束 / Upstream constraints | 本仓库既有 IaC 布局与 CI 纪律（`sites/*`、`00-account`、`.github/workflows/infra.yml`、`make check`） |
| 语言 / Language | 中英双语标题，正文中文 / bilingual headings, Chinese body |

---

## 0. 这份规格是什么 / What this is

这是一套**可直接开发**的规格：读完可以向仓库提交代码，包含全部领域规则、数据模型、接口契约与验收断言。

*This is an implementable specification: after reading it a team can start committing code, with every domain rule, data model, interface contract and acceptance assertion already fixed.*

它定义三件事，且只定义这三件事：

1. **平台要建成什么样**——领域模型、模块边界、数据模型、API 契约、页面与交互。
2. **平台如何与既有 Terraform 体系对接**——怎么读 state、怎么触发 CI、怎么遵守 `make check` 与三轨发布门。
3. **怎样算做完**——每个阶段的验收断言，可执行、可复现。

*It defines exactly three things: what the platform must become, how it integrates with the existing Terraform estate, and what "done" means as executable acceptance assertions.*

规格正文只给结论。所有判断以**决策记录**（`07-decisions.md`）的形式给出：一条一句理由，并写明代价与影响范围。

*The body gives conclusions only. Every judgement appears in `07-decisions.md` as a decision, a one-line rationale, an accepted cost and its blast radius.*

---

## 1. 交付物清单 / Deliverables

| 文件 / File | 内容 / Contents | 读者 / Reader |
| --- | --- | --- |
| `README.md` | 本文件：边界、目标态差异、阅读顺序 / this file | 全体 / all |
| `00-summary.md` | **架构总结**：七个判断、三平面、站点与层、统一模型、图谱、成本、漂移、边界 / architecture summary | **先读这个** / read first |
| `01-overall-design.md` | 目标、范围、约束、原则、三平面架构、多云统一模型、栈图谱、技术栈、非功能 / goals, scope, principles, three-plane architecture, unified multi-cloud model, stack graph, stack, NFRs | 架构、后端、前端 / arch, backend, frontend |
| `02-domain-specs.md` | 7 个领域模块的规格：职责、规则编号、输入输出、降级、验收 / per-module specification with numbered rules | 后端 / backend |
| `03-data-model.sql` | PostgreSQL 16 完整 DDL（Flyway `V1__core.sql`）/ full DDL | 后端 / backend |
| `04-api-contract.md` | REST 契约：鉴权、错误码、分页、幂等、逐端点 / REST contract | 后端、前端 / backend, frontend |
| `05-frontend-spec.md` | 9 个页面的布局、数据来源、交互、图表规格、设计 token / 9 screens | 前端 / frontend |
| `06-roadmap-acceptance.md` | 5 个阶段、每阶段验收断言、上线 Runbook / phased roadmap + executable acceptance | 全体、验收 / all, QA |
| `07-decisions.md` | 决策记录（结论 + 一句理由 + 代价）/ decision records | 全体 / all |
| `08-execution-plan.md` | 从当前仓库走到目标态的五个执行批次、命令、验证与回滚 / execution plan | 执行团队 / implementers |
| `diagrams/*.svg` | 8 张图，独立可缩放，无外部依赖 / 8 standalone SVGs | 全体 / all |

图表索引 / *Diagram index*：

| 图 / Diagram | 说明 / Description | 引用位置 / Referenced in |
| --- | --- | --- |
| `01-layered-architecture.svg` | 平台七层总体架构 / seven-layer overall architecture | `01` §5 |
| `02-modulith-modules.svg` | Spring Modulith 模块与依赖方向 / modules and dependency direction | `01` §6 |
| `03-cost-pipeline.svg` | 成本管道 S0→S4 / cost pipeline | `02` §4 |
| `04-drift-detection.svg` | 漂移双通道 A/B / dual-channel drift detection | `02` §3 |
| `05-roadmap.svg` | 五阶段路线 / five-phase roadmap | `06` §1 |
| `06-multicloud-model.svg` | 三云统一模型 / unified multi-cloud model | `01` §7 |
| `07-service-graph.svg` | 栈图谱三视图 / stack graph, three views | `01` §8、`02` §2 |
| `08-multi-plane-dr.svg` | 三平面、容灾资产、切换矩阵 / planes, DR assets, failover matrix | `00` §3、`01` §5.4、`02` §9 |

---

## 2. 系统边界 / System boundary

### 2.1 平台负责 / In scope

| 能力域 / Domain | 一句话 / One line |
| --- | --- |
| **INV** 资源目录 | 把 AWS / GCP / Azure 的资源按「项目 → 层 → 资源」组织，并把它们串成栈图谱 |
| **DRIFT** 漂移检测 | 双通道：日频 API 属性对账 + 周频 `terraform plan -refresh-only` |
| **FIN** 成本 | FOCUS 1.x 归一，Billed / Amortized / Allocated 三口径 + 差异桥，按项目/层/服务大类分摊 |
| **DEL** 发布治理 | 读 GitHub Actions 真实运行记录，建立 PR → commit → plan → apply → 资源变更的链路 |
| **OBS** 观测与事件 | 指标/日志/告警的只读聚合与事件总线视图 |
| **SEC** 安全姿态 | 公网暴露面、加密、IAM 越权、密钥位置四类检查 |
| **NOT** 通知 | 平台内事件 → 飞书卡片 / 邮件的路由与静默 |
| **PLT** 平台自身 | 身份、授权、任务编排、审计、配置 |

*INV, DRIFT, FIN, DEL, OBS, SEC, NOT, PLT — eight domains, one Spring Modulith module each except PLT which is cross-cutting.*

### 2.2 平台明确不做 / Out of scope

| 不做 / Not doing | 原因 / Why |
| --- | --- |
| 不写云端资源、不执行 `terraform apply` | 写操作的唯一入口是 GitHub Actions 手动 dispatch；平台只**触发**，不持有 apply 凭据 |
| 不替代 Terraform / 不做 IaC 编辑器 | 代码是唯一真相，平台只读代码与 state |
| 不做实时告警引擎 | 告警在 CloudWatch / Cloud Monitoring / Azure Monitor；平台只聚合展示与关联 |
| 不做日志存储 | 日志留在各云与 Loki；平台只做跳转与查询编排 |
| 不改 DNS、不执行容灾切换 | 平台展示切换矩阵与演练记录，切换动作由 runbook 人工执行 |
| 不做多租户、不做对外 SaaS | 单人/小团队内部工具，单租户 |
| 首期不做自动修复（auto-remediation） | 误判成本高于收益 |

*No writes, no IaC editing, no alerting engine, no log storage, no DNS changes, no auto-remediation, single tenant.*

### 2.3 谁在用 / Users

| 角色 / Role | 主要动作 / Primary actions |
| --- | --- |
| Owner（默认唯一账号） | 看总览、查成本、确认发布、开关管理站 |
| Reviewer（可增） | 只读全部页面，可评论，不可触发 |
| 机器身份（CI） | 推送采集数据（snapshot 写入），只写 own 前缀 |

---

## 3. 目标态与现状 / Target state vs. today

本规格描述的是**目标态**，不迁就当前目录编号与 state 位置。所有差异收敛为六条，逐条的执行步骤、命令与回滚写在 `08-execution-plan.md`。

*This spec describes the target state. Every difference from today collapses into the six items below; steps, commands and rollback live in `08-execution-plan.md`.*

| # | 项目 | 现状 | 目标态 | 执行方式 |
| --- | --- | --- | --- | --- |
| E1 | 层编号 | `00-bootstrap / 10-storage / 20-edge / 30-compute`，两站顺序不一致 | `00-bootstrap / 01-storage / 02-data / 03-compute / 04-edge`，**层号 = apply 顺序**，三站同构 | 目录重排 + 新建 `02-data` 承接数据类资源 |
| E2 | state 后端 | S3 `chenyouyi-tfstate` + `use_lockfile` | **Azure Blob + lease**（`tfstate` 容器，key `{site}/{layer}.tfstate`） | 新后端空 state 起步，**不迁移、销毁重建** |
| E3 | 站点数量 | `00-account` + photos + management | 上述 + **`sites/platform`**（平台自身五个层，部署在 Azure） | 新建 |
| E4 | 权威 DNS | Route 53 | **Azure DNS**，切换走 DNS 层 failover | 迁移域名托管 + TTL 分层 |
| E5 | 共享层 | `00-account` 只管 AWS（state 桶、OIDC、infra 角色） | `s00-account`：state 容器、跨云 OIDC、三云只读角色、KMS、日志归档 | 重命名 + 扩到三云 |
| E6 | 数据类资源位置 | DynamoDB / SQS 在 `30-compute` 里 | 移到 `02-data` | 随 E1 的销毁重建一起完成 |

### 3.1 站点与层（目标态）

| 站点 | 层 | 顺序 | kind |
| --- | --- | --- | --- |
| `s00-account` | — | 0 | account |
| `sites/photos` | `00-bootstrap` `01-storage` `02-data` `03-compute` `04-edge` | 1–5 | 同名 kind |
| `sites/management` | 同上（`mgmt_enabled` 门控） | 1–5 | 同上 |
| `sites/platform` | 同上 | 1–5 | 同上 |

### 3.2 `site.yaml` 契约 / The site manifest

每站根目录一份，声明站点属性与层清单。**它不携带 apply 顺序**——顺序由层号唯一决定。

```yaml
# sites/photos/site.yaml
site: photos
cloud: aws
state:
  backend: azblob
  container: tfstate
  key_pattern: "{site}/{layer}.tfstate"
gate: null                  # management 站为 mgmt_enabled
layers:
  - { dir: 00-bootstrap, kind: bootstrap }
  - { dir: 01-storage,   kind: storage }
  - { dir: 02-data,      kind: data }
  - { dir: 03-compute,   kind: compute, depends_on: [02-data] }
  - { dir: 04-edge,      kind: edge,    depends_on: [03-compute] }
```

`make check` 新增六条断言（与既有 91 项并列）：

| 断言 | 内容 |
| --- | --- |
| A1 | 层目录编号必须恰为 `00..0n` 连续，无空洞、无重复 |
| A2 | `kind` 属于 `{bootstrap, storage, data, compute, edge, observe}`，且与目录编号的默认语义一致（`02`→data，`03`→compute，`04`→edge） |
| A3 | `depends_on` 中的层编号必须小于本层 |
| A4 | 跨站点引用只允许指向 `s00-account` |
| A5 | `gate` 非空时，被门控层必须显式列在 `gated_layers` |
| A6 | 层目录必须存在，且 `backend.hcl` 的 `key` 与 `key_pattern` 一致 |

`infra.yml` 的 apply 矩阵按层号排序生成，站点之间可并行、站点之内 `max-parallel: 1`：

```yaml
strategy:
  max-parallel: 1
  matrix:
    layer: ${{ fromJSON(needs.resolve.outputs.layers) }}   # 由 scripts/layers.mjs 按层号升序生成
```

### 3.3 不动的部分 / What stays

| 项目 | 说明 |
| --- | --- |
| 仓库边界 | 只管基础设施，站点内容一律不入库 |
| 三轨发布门 | PR 只 plan / push main 只 plan / 手动 dispatch + `confirm` 才 apply |
| `make check` 的定位 | 离线、秒级、抓引用与密钥，与 `terraform validate` 互补 |
| 密钥策略 | 敏感值走 SSM SecureString，Terraform 只保证存在与加密 |
| 模块纪律 | 模块内不出现环境字面量，每个模块有 `variables.tf` + `outputs.tf` |
| 两段式启用 | `enable_functions` 这类先后顺序开关保留，平台不改它 |

## 4. 术语 / Glossary

| 术语 / Term | 定义 / Definition |
| --- | --- |
| **site** | 一个可独立销毁/重建的部署单元，如 `photos`、`management`、`platform` |
| **layer** | site 内的一个 Terraform 根模块，有自己的 state key，如 `03-compute` |
| **layer kind** | 层的语义类型，取值六种；与目录编号一一对应（`02`=data、`03`=compute、`04`=edge） |
| **layer no** | 层目录编号，**同时就是 apply 顺序**；站点内从 `00` 连续递增 |
| **stack** | `(site, layer)` 二元组，平台图谱的节点 |
| **scope_node** | 可嵌套的资源域树（账号 → 项目 → 环境 → 层），LTREE 实现 |
| **canonical_type** | 跨云归一后的资源类型，如 `storage.object` |
| **FOCUS** | FinOps Open Cost and Usage Specification 1.x |
| **basis** | 成本口径，`BILLED` / `AMORTIZED` / `ALLOCATED` |
| **drift** | 线上实际配置与代码/state 期望的差异 |
| **gate** | 受管开关，如 `mgmt_enabled` |
| **plan run / apply run** | GitHub Actions 的一次 workflow run |

---

## 5. 阅读顺序 / Reading order

| 你要做什么 / If you are… | 按序读 / Read in order |
| --- | --- |
| 先弄懂整体在想什么 | `00`（十分钟）→ `01` §5–8 |
| 改造现有仓库 / 迁移到目标态 | `08` → `README` §3 |
| 搭后端骨架 | `01` §5–6 → `02` 全文 → `03` → `04` |
| 搭前端 | `05` → `04` §2–3 → `01` §7–8 |
| 接数据采集 | `02` §2–5 → `03` 表 `raw_*` / `resource` → `04` §5 |
| 定验收 | `06` → `02` 每章「验收」小节 |
| 判断某个设计为什么这样 | `07` 按编号查 |

---

## 6. 版本与兼容 / Versioning

- 规格版本与平台 API 版本独立：规格 `V2.x`，API `v1`。
- `spec/V2` 内的破坏性变更写新文件（`V3`），不就地改已发布版本的语义。
- 数据模型变更走 Flyway 增量脚本，`V1__core.sql` 一旦发布不再修改。
