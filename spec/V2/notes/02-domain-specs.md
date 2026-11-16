# 02 · 领域模块规格 / Domain Module Specifications

| 项 / Item | 值 / Value |
| --- | --- |
| 状态 / Status | Approved for implementation |
| 依赖 / Depends on | `01` §6–8、`03`、`04` |
| 规则编号 / Rule prefix | `R-<模块>-nn`，全局唯一，测试用例以其为名 |

每个模块按同一结构写：**职责 → 输入 → 规则 → 输出 → 降级 → 验收**。规则编号是测试用例名，`R-INV-05` 对应 `InventoryRulesTest#r_inv_05_*`。

---

## 1. INV · 资源目录 / Inventory

### 1.1 职责

维护三云资源的**唯一真源**：每个资源挂在 `scope_node` 树的一个叶子上，带 `canonical_type`、规范标签、所属 `(site, layer)`、生命周期状态、月成本引用。

*Single source of truth for every cloud resource: where it lives, what type it is, which stack owns it, whether it still exists.*

### 1.2 输入

| 源 / Source | 接口 / API | 频率 | 产出 |
| --- | --- | --- | --- |
| AWS | Resource Explorer `Search` + 按类型的 `Describe*` 补属性 | 15 min | `raw_aws_*` → `resource` |
| GCP | Cloud Asset Inventory `ListAssets`（含 `relatedAssets`） | 15 min | `raw_gcp_*` → `resource` |
| Azure | Resource Graph `Resources` + `ResourceChanges` | 15 min | `raw_azure_*` → `resource` |
| 代码 | CI `iac-snapshot.yml` 产出的 `state.json` | 每次 apply 后 | `iac_resource`（代码侧期望） |
| 声明 | `sites/*/site.yaml` | 每次 commit | `stack` |

### 1.3 规则

| # | 规则 / Rule |
| --- | --- |
| **R-INV-01** | 资源主键是 `(cloud, native_id)`。`native_id` 原样存储，**不解析** ARN / 资源 ID 的内部结构。 |
| **R-INV-02** | 每个资源必须有 `scope_path`。无法归类的挂到 `unassigned`，但**必须**计入所有分母，禁止静默丢弃。 |
| **R-INV-03** | 资源的 `layer_id` 优先取规范标签 `layer`；无标签时由 `state.json` 的 address 前缀反查；仍无则取 `scope_node` 的 layer 节点；三者都无 → `layer_id = null` 且 `inferred_scope = false`。 |
| **R-INV-04** | 资源消失（上游不再返回）不删除行，置 `lifecycle_state = 'VANISHED'` 并记 `vanished_at`。连续 3 次采集都未出现才转 `GONE`。 |
| **R-INV-05** | 资源"被删除"与"消失"必须区分：有 CloudTrail / Audit Log 的删除事件 → `DELETED`；无事件只是查不到 → `VANISHED`（通常是权限或索引问题）。 |
| **R-INV-06** | 属性变更写新行，旧行 `superseded_by` 指向新行。同一 `(cloud, native_id)` 的当前版本由 `superseded_by IS NULL` 唯一确定（部分唯一索引强制）。 |
| **R-INV-07** | `canonical_type` 只从 `type_mapping` 表取，未命中写 `unknown`，**不改写云内类型**。云内类型永远保留在 `native_type`。 |
| **R-INV-08** | 采集到 6 个规范标签之外的标签全部保留在 `tags_raw`（JSONB），不做过滤，供后续发现新的分摊维度。 |
| **R-INV-09** | 标签键比较一律小写；展示保留云端原始写法。同名不同大小写视为同一键，取**首次出现**的写法用于展示，并在 `tag_conflicts` 记录冲突。 |
| **R-INV-10** | `mgmt_enabled` 等受管开关：平台读 `site.yaml` 的 `gate` 声明 + tfvars 实际值，两者不一致或与资源实际存在性不一致 → 产生 `GATE_MISMATCH` 结论。 |
| **R-INV-11** | 采集必须写入 `job_run`，包含 `rows_in`（上游返回条数）、`rows_out`（入库条数）。两者差值超过 5% 时标 `PARTIAL`，页面显示「部分数据」。 |
| **R-INV-12** | 单个云账号采集失败不影响其它账号：按 `(cloud, scope)` 独立事务，失败只降级该 scope。 |
| **R-INV-13** | `layers` 元数据以 `site.yaml` 为唯一来源；`make check` 断言不通过时平台**拒绝**加载该站点，页面显示 manifest 错误，而不是猜测。 |
| **R-INV-14** | 层类型适配度：`canonical_type` 组与 `layer.kind` 不匹配时记 `fit = 0`，进「层内异类资源」清单，**不阻断任何流程**。 |

### 1.4 输出

| 端点 | 说明 |
| --- | --- |
| `GET /api/v1/inventory/resources` | 分页资源列表，支持 `site/layer/type/cloud/state/q` 筛选 |
| `GET /api/v1/inventory/resources/{cloud}/{nativeId}` | 单资源详情（含标签、属性历史、所属栈、成本引用、漂移） |
| `GET /api/v1/inventory/tree` | `scope_node` 树 + 每节点资源数/成本 |
| `GET /api/v1/inventory/stacks` | 全部 stack 及其健康度 |
| `GET /api/v1/inventory/health` | 目录健康度：未归类数、unknown 类型数、标签缺失率、采集新鲜度 |
| `GET /api/v1/inventory/gates` | 受管开关状态与一致性 |

### 1.5 降级

| 场景 | 行为 |
| --- | --- |
| 某云 API 全挂 | 该云资源标记 `stale`，页面顶部横幅「AWS 数据 2 小时未更新」，其余两云正常 |
| 首次全量采集未完成 | 目录页显示骨架 +「正在建立基线（约 6 分钟）」，不显示 0 |
| `state.json` 缺失 | 栈图谱仍可画（结构来自 `site.yaml`），边降级为仅级别 1/6，并提示「代码侧证据缺失」 |

### 1.6 验收

- 三云资源总数与各自控制台计数误差 < 1%。
- 随机 20 个资源，`scope_path` 与 `layer_id` 人工核对全部正确。
- 强制篡改一个资源的标签后，下一次采集能在 16 分钟内反映。
- `unassigned` 数量在真实数据上 < 3%。

---

## 2. GRAPH · 栈图谱 / Stack graph

### 2.1 职责

把 `(site, layer)` 画成用户能读的拓扑，并把成本、漂移、发布、风险挂到节点和边上。

### 2.2 规则

| # | 规则 |
| --- | --- |
| **R-GRP-01** | 节点只来自 `site.yaml` 声明的层，**不生成"服务"抽象层**。 |
| **R-GRP-02** | 边必须带 `provenance`（六级之一）、`weight`、`evidence` 文本。无 evidence 不入库。 |
| **R-GRP-03** | 同一对节点多条证据取最高级别，其余保留在 `evidence[]`。 |
| **R-GRP-04** | 级别 6（标签推断）的边默认不渲染，返回给前端时带 `hidden_by_default = true`。 |
| **R-GRP-05** | 边方向恒为「被依赖 → 依赖」。级别 1 的边按层号相邻生成，跨 `kind` 不跳级。 |
| **R-GRP-06** | 上游节点消失或降级时，依赖它的边标 `broken = true`（如 state 引用指向已销毁的层）。 |
| **R-GRP-07** | 影响面计算：选中节点后做反向 BFS 三跳，返回 `impact_1 / impact_2 / impact_3` 三组，**每跳必须可展开到具体节点**。 |
| **R-GRP-08** | 图谱健康度 = 孤立节点数（无任何边）、断边数、级别分布、覆盖层数，四项都要能点进去。 |
| **R-GRP-09** | 节点半径/面积映射月成本时用**平方根**缩放，否则成本差异会让小额节点不可见。 |
| **R-GRP-10** | 布局由后端算好的固定坐标提供（`layout_x/y`），前端不跑力导向布局——同一份数据每次打开位置必须一致。 |

### 2.3 输出

| 端点 | 说明 |
| --- | --- |
| `GET /api/v1/graph?site=&view=layer\|flow\|cost` | 节点 + 边 + 布局 + 图例元数据 |
| `GET /api/v1/graph/{site}/{layer}/impact` | 影响面三跳 |
| `GET /api/v1/graph/health` | 图谱健康度四项明细 |

### 2.4 验收

- 16 个节点、按真实证据生成 ≥ 12 条边；级别分布可解释。
- 抽 5 条边核对 `evidence`，全部能在代码或 `state.json` 里找到对应原文。
- 选中 `photos/01-storage` 时，`impact_1` 必须包含 `02-data` 与 `03-compute`。

---

## 3. DRIFT · 漂移检测 / Drift detection

![漂移双通道](diagrams/04-drift-detection.svg)

### 3.1 职责

回答一个问题：**线上真实状态与代码描述的状态是否一致，差在哪。**

两条通道互补，缺一不可：

| 通道 / Channel | 机制 | 频率 | 覆盖 | 能发现 |
| --- | --- | --- | --- | --- |
| **A 属性对账** | 云 API 实时属性 vs `state.json` 中的属性 | 每日 02:00 | 全部受管资源 | 属性被改（标签、策略、加密、生命周期、超时、内存） |
| **B plan 对账** | CI 跑 `terraform plan -refresh-only`，产出 `plan.json` | 每周日 03:00 | 全部 scope | 属性 + **代码与 state 的关系**（漏提交、手改后代码没跟上、state 丢失） |

### 3.2 规则

| # | 规则 |
| --- | --- |
| **R-DRF-01** | 漂移以 `(resource_id, attribute_path)` 为最小单位，不聚合到资源级别，否则无法判断轻重。 |
| **R-DRF-02** | 每条漂移必须有 `expected` 与 `actual` 两个值，且都可序列化为 JSON。缺失任一值即为无效结论，不入库。 |
| **R-DRF-03** | 比较前先过 `ignore_rules`：平台维护一张受控忽略表（如 `tags.last-modified`、`aws:cloudformation:*` 系统标签），忽略必须写明原因与到期日。 |
| **R-DRF-04** | 严重度固定四档，判定只看两个维度——属性敏感性 × 是否影响可用性： |
| | `CRITICAL`：安全或可用性直接受损（加密关闭、公开访问开启、删除保护关闭、SG 放通 0.0.0.0/0） |
| | `HIGH`：行为改变（内存/超时/并发、缓存策略、生命周期规则） |
| | `MEDIUM`：标签、描述、非关键参数 |
| | `LOW`：仅格式差异（顺序、空白、默认值显式化） |
| **R-DRF-05** | 通道 B 的 plan 差异必须区分三类：`CODE_BEHIND`（线上改了代码没改）、`UNCOMMITTED`（本地改了没提交）、`STATE_DRIFT`（state 与线上不符）。三类的处置动作完全不同。 |
| **R-DRF-06** | 漂移生命周期：`OPEN → ACKNOWLEDGED → RESOLVED / IGNORED / EXPIRED`。`ACKNOWLEDGED` 必须填 `reason` 与 `review_by`（默认 7 天后）。 |
| **R-DRF-07** | 同一 `(resource, attribute)` 再次检出时复用原 `finding` 行更新 `last_seen_at`，不新建行；`occurrence_count` 累加。 |
| **R-DRF-08** | 漂移消失时不直接置 `RESOLVED`，先置 `PENDING_VERIFY`，**下一次采集仍不出现**才 `RESOLVED`（防抖）。 |
| **R-DRF-09** | 通道 B 的 plan 若因权限或 provider 报错而失败，记 `INCONCLUSIVE`，**不得**当作"无漂移"。页面必须区分「0 条漂移」与「未能判断」。 |
| **R-DRF-10** | 漂移与发布关联：`first_seen_at` 前后 6 小时内有该 scope 的 apply run，则标注 `likely_caused_by = <run_id>`，只做提示不做断言。 |
| **R-DRF-11** | 漂移覆盖率 = 已对账资源数 / 受管资源数，必须与漂移数一起展示。覆盖率 < 95% 时页面警示。 |
| **R-DRF-12** | 忽略规则到期后自动失效，对应漂移重新进入 `OPEN`，并通知。 |

### 3.3 输出

| 端点 | 说明 |
| --- | --- |
| `GET /api/v1/drift/findings?severity=&site=&layer=&state=` | 漂移列表 |
| `GET /api/v1/drift/findings/{id}` | 单条详情：expected/actual diff、证据、可能来源 run |
| `POST /api/v1/drift/findings/{id}/ack` | 认领，body 必填 `reason`、`reviewBy` |
| `POST /api/v1/drift/findings/{id}/ignore` | 忽略，body 必填 `reason`、`expiresAt` |
| `GET /api/v1/drift/runs` | 两通道最近运行记录与结论（含 `INCONCLUSIVE`） |
| `GET /api/v1/drift/coverage` | 覆盖率与未覆盖清单 |

### 3.4 降级

通道 A 依赖 `state.json` 新鲜度。若某 scope 的 `state.json` 超过 7 天未更新，该 scope 的通道 A 结果标 `STALE_BASELINE`，不计入漂移数，只计入"未覆盖"。

### 3.5 验收

- 手动改一个 S3 桶的 lifecycle 规则，次日通道 A 报出 `HIGH`，`expected/actual` 正确。
- 手动改一个 Lambda 的内存，通道 A 报 `HIGH`，同一周通道 B 报 `CODE_BEHIND`。
- 误报率抽样 50 条 < 5%（人工判定）。
- 通道 B 在权限不足时显示 `INCONCLUSIVE` 而不是 0 条。

---

## 4. FIN · 成本 / FinOps

![成本管道](diagrams/03-cost-pipeline.svg)

### 4.1 职责

把三张格式不同的账单，变成**一张可分摊、可解释、可回溯**的成本表。

### 4.2 管道五个阶段（S0 → S4）

| 阶段 | 输入 | 处理 | 输出表 | 前提 |
| --- | --- | --- | --- | --- |
| **S0 接入** | CUR 2.0 Parquet / GCP BQ 导出 / Azure Cost Export | 只做读取与字段映射，不改语义 | `raw_cost_line` | 三云导出已配置 |
| **S1 归一** | `raw_cost_line` | 映射到 FOCUS 1.x 列 | `cost_fact` | — |
| **S2 三口径** | `cost_fact` | 计算 BILLED / AMORTIZED / ALLOCATED | `cost_fact.basis` 三组 | — |
| **S3 分摊** | `cost_fact` + `resource` + `stack` | 按 scope / 标签 / 资源归属分摊到 stack | `allocation` | S1 完成 |
| **S4 差异桥** | 三口径结果 | 逐项解释差额来源 | `cost_bridge` | S2 完成 |

### 4.3 S0 的现实约束

账单管道在规格落地时**尚未建立**。平台必须支持三级精度，并明确标注：

| 级别 | 数据源 | 精度 | 标注 |
| --- | --- | --- | --- |
| **L0 汇总** | Cost Explorer `GetCostAndUsage`（月粒度，按 SERVICE 分组） | 只能到服务大类，不能到资源 | `precision = 'AGGREGATE'` |
| **L1 明细** | CUR 2.0 → S3 Parquet | 到资源、到标签 | `precision = 'DETAIL'` |
| **L2 摊销** | CUR 含 `reservation/` 与 `savings_plan/` 行 | 支持摊销与承诺折扣 | `precision = 'AMORTIZED'` |

规则：

| # | 规则 |
| --- | --- |
| **R-FIN-01** | 任何成本数字必须携带 `precision` 与 `basis`，前端必须显示。**不允许出现没有精度标注的金额。** |
| **R-FIN-02** | S0 只做字段映射，不做任何单位换算以外的事情；货币换算记录 `fx_rate` 与 `fx_date`。 |
| **R-FIN-03** | `cost_fact` 主键 = `(cloud, account_id, charge_period_start, service, resource_id, charge_type, basis)`；同一键重复入库时按 `version` 递增，保留历史。 |
| **R-FIN-04** | 三口径定义固定： |
| | `BILLED`：账单发票口径，含税与抵扣，按账期真正开出的金额 |
| | `AMORTIZED`：把一次性预付（RI/SP/CUD）按天摊到使用期 |
| | `ALLOCATED`：在 AMORTIZED 基础上去掉共享成本下沉（如数据传输、支持费）后分摊到 stack |
| **R-FIN-05** | 差异桥必须能逐项解释 `ALLOCATED` 与 `BILLED` 的差额，分五类：未分摊共享成本、承诺折扣未摊销部分、跨期调整、税费、汇率差。每类都要有金额与占比。 |
| **R-FIN-06** | 未分配金额（`allocated = null`）必须单独成行展示，**不允许**摊进任何一个 stack 来"抹平"。目标 < 2%，超过即警示。 |
| **R-FIN-07** | 分摊顺序固定，不可跳步：① 资源直接归属 → ② 规范标签 `app/site/layer` → ③ `scope_node` 推断 → ④ 剩余进 `unallocated`。每一步的覆盖量要能看到。 |
| **R-FIN-08** | 分摊规则版本化（`allocation_rule.version`），重建分摊时写新版本，历史查询按当时版本复现。 |
| **R-FIN-09** | 账期关闭判定：某云某账期的明细在 T+2 后仍缺行、或总额与该云官方汇总偏差 > 1%，账期标 `PROVISIONAL`，页面显式提示。 |
| **R-FIN-10** | 成本趋势图必须能切换口径，且切换后同一时间点的数值变化要有过渡提示，不能让用户误以为成本变了。 |
| **R-FIN-11** | 预算与异常：按 stack 设月预算，超 80% / 100% 产生事件；环比波动超过 3 倍且绝对额 > ¥200 的 stack 进「异常」清单。 |
| **R-FIN-12** | 节省建议只允许四类可计算项：闲置资源（CPU/连接数低于阈值连续 14 天）、未挂载存储、超额预留、跨区流量。**每条建议必须带估算公式与依据数据。** |
| **R-FIN-13** | 成本明细导出（CSV/Parquet）必须与页面同一查询同口径，导出文件头写入查询参数与生成时间。 |

### 4.4 输出

| 端点 | 说明 |
| --- | --- |
| `GET /api/v1/cost/summary?period=&basis=&groupBy=site\|layer\|service\|cloud` | 汇总 |
| `GET /api/v1/cost/trend?from=&to=&basis=&granularity=day\|month` | 趋势 |
| `GET /api/v1/cost/bridge?period=` | 三口径差异桥 |
| `GET /api/v1/cost/allocations?period=&site=` | 分摊明细 |
| `GET /api/v1/cost/savings?period=` | 节省建议 |
| `GET /api/v1/cost/budgets` | 预算与消耗 |
| `POST /api/v1/cost/rebuild` | 重建分摊（异步任务，返回 `jobId`） |
| `GET /api/v1/cost/export?...` | 导出 |

### 4.5 降级

| 场景 | 行为 |
| --- | --- |
| 只有 L0 汇总 | 目录/图谱的成本字段显示"服务级估算"，不参与 stack 级排名 |
| GCP 导出延迟 | 该云单独标 `PROVISIONAL`，总额显示"不含 GCP" |
| 汇率缺失 | 用上一可用汇率并在页面标注日期 |

### 4.6 验收

- 三云月度总额与各自控制台账单误差 < 1%（L1 数据）。
- 差异桥的五类金额相加等于 `BILLED - ALLOCATED`，误差为 0（整数分）。
- 未分配比例 < 2%（真实数据）。
- 任一 stack 的月成本，能点开看到构成它的成本行。

---

## 5. DEL · 发布治理 / Delivery governance

### 5.1 职责

把 GitHub Actions 的真实运行记录，与资源变更对上，回答：**这次改动是谁、因为什么、改了什么、有没有经过 plan 审核。**

### 5.2 输入

| 源 | 接口 | 频率 | 说明 |
| --- | --- | --- | --- |
| workflow runs | `cicd/workflows.json`（`cicd-snapshot.yml` 产出） | 跟随快照刷新 | **默认路径**：平台不持令牌做常规采集 |
| jobs / steps | `GET .../actions/runs/{id}/jobs` | 按需 | 只读令牌，点开详情时才取 |
| 产物 | Blob `snapshot/{site}/{layer}/{ts}.json`（`iac-snapshot.yml` 产出） | 每次 apply 后 | 资源变更差集的唯一依据 |
| commit | `GET .../commits/{sha}` | 按需 | 只读令牌 |
| PR | `GET .../pulls/{number}` | 按需 | 只读令牌 |

### 5.3 规则

| # | 规则 |
| --- | --- |
| **R-DEL-01** | 常规运行数据从 `cicd/workflows.json` 快照读取，平台不为采集持有 GitHub 令牌。唯一需要令牌的动作是 dispatch 一次 `infra.yml`（`Actions: write`），且平台**不写**代码、不合并 PR、不改 workflow 文件。 |
| **R-DEL-02** | 一次 apply 必须能关联到：`run_id`、`commit_sha`、`pr_number`（如有）、`layers[]`、`resources_changed[]`。关联不上的显示「未关联」而不是猜。 |
| **R-DEL-03** | 资源变更集合由两次 `state.json` 快照做差得到，不用 plan 文本解析。差集分 `ADDED / CHANGED / DESTROYED / REPLACED`。 |
| **R-DEL-04** | 发布门状态按仓库三轨铁律展示，不可绕过：PR → 只 plan；push main → 只 plan；手动 dispatch + `confirm=apply` → 才 apply。平台展示但不能改变这个事实。 |
| **R-DEL-05** | 触发 apply 需要**双人确认**：发起人 + 确认人（同一个人不可兼任）。平台上体现为两个不同的登录会话。 |
| **R-DEL-06** | 触发前强制展示：目标 site/layer、当前 `plan` 摘要、将被销毁的资源数、漂移中 `CRITICAL` 条数。任一为"未知"则禁止触发。 |
| **R-DEL-07** | 触发后平台只做观察，**不参与执行**；超时（30 分钟）未结束只显示"仍在运行"，不主动取消。 |
| **R-DEL-08** | 发布风险评级 `LOW/MEDIUM/HIGH`：含 `DESTROYED` 或 `REPLACED` → 至少 MEDIUM；触及 `edge` 或 `bootstrap` 层 → 至少 MEDIUM；含 `CRITICAL` 漂移未处理 → HIGH。 |
| **R-DEL-09** | 发布成功率与 lead time 由 run 数据计算，公式写在页面上，不可自定义口径（避免"美化指标"）。 |
| **R-DEL-10** | `mgmt_enabled` 开/关也是一次发布，必须与普通发布同样留痕。 |

### 5.4 输出

| 端点 | 说明 |
| --- | --- |
| `GET /api/v1/delivery/runs?repo=&status=&from=&to=` | run 列表 |
| `GET /api/v1/delivery/runs/{id}` | 详情：jobs、耗时、资源变更、风险评级 |
| `GET /api/v1/delivery/changes?resource=&from=&to=` | 按资源的变更历史 |
| `POST /api/v1/delivery/dispatch` | 触发 apply（需双人确认令牌） |
| `GET /api/v1/delivery/metrics?period=` | 成功率、频率、lead time、MTTR |

### 5.5 验收

- 任一 apply run 在结束后 5 分钟内出现在平台，且资源变更集合与 `terraform` 输出一致。
- 触发流程在缺少第二人确认时返回 403，且有审计记录。
- 风险评级规则对 3 个历史 run 的判定与人工判断一致。

---

## 6. OBS · 观测与事件 / Observability

### 6.1 职责

不做监控系统，做**监控的目录**：让用户知道"这个 stack 有哪些指标、现在什么水平、异常从哪看"。

### 6.2 规则

| # | 规则 |
| --- | --- |
| **R-OBS-01** | 指标只聚合**预定义的少量关键指标**（每 stack ≤ 8 个），不做任意查询面板。 |
| **R-OBS-02** | 每个指标卡必须带：当前值、对比基准（昨日/上周同刻）、数据源、跳转到原生控制台的链接。 |
| **R-OBS-03** | 指标数据缺失时显示「无数据」并给出可能原因（未配置告警、权限不足、资源不存在），不显示 0。 |
| **R-OBS-04** | 告警聚合按 `(stack, severity, 首见时间)` 归并，同一告警 30 分钟内合并为一条，展示 `occurrence_count`。 |
| **R-OBS-05** | 观测数据保留 24 小时**快照**（每 5 分钟一点），用于平台自身降级时仍能看到趋势；长期数据仍在原生系统。 |
| **R-OBS-06** | 日志不存储，只提供组装好的查询链接（CloudWatch Logs Insights / GCP Logging / Azure Log Analytics / Loki），链接必须带时间范围与过滤条件。 |
| **R-OBS-07** | SLO 只对三个对象定义：站点可用性、API P95 延迟、处理队列积压时长。SLO 未达标时展示"预算燃烧率"而不是单纯红点。 |

### 6.3 输出

| 端点 | 说明 |
| --- | --- |
| `GET /api/v1/observe/metrics?stack=&window=` | 关键指标 |
| `GET /api/v1/observe/alerts?state=&severity=` | 聚合后的告警 |
| `GET /api/v1/observe/links?stack=&kind=logs\|traces\|metrics` | 原生系统跳转链接 |
| `GET /api/v1/observe/slo` | SLO 与燃烧率 |

### 6.4 验收

- 每个 stack 的指标卡能点开到原生控制台且时间范围正确。
- 断开某云 API 后，指标卡显示「无数据 + 原因」，页面不崩。

---

## 7. SEC · 安全姿态 / Security posture

### 7.1 职责

四类**可判定**的检查。不做渗透、不做合规扫描、不给风险评分（评分是伪精度）。

### 7.2 四类检查

| 类 / Class | 检查项 | 判定依据 |
| --- | --- | --- |
| **暴露面** | 公网可达的资源、`0.0.0.0/0` 安全组规则、公开读的桶、未启用 WAF 的入口 | 资源属性 |
| **加密** | 静态加密未启用、TLS 版本 < 1.2、证书 30 天内到期 | 资源属性 |
| **身份** | 角色策略含 `*:*`、信任策略允许任意主体、长期 access key 存在且 90 天未轮换 | IAM 数据 |
| **密钥位置** | 明文密钥出现在 tfvars / 环境变量 / 代码中（模式匹配 + 熵检测） | 仓库快照 |

### 7.3 规则

| # | 规则 |
| --- | --- |
| **R-SEC-01** | 每条结论必须给出：资源、证据（属性路径或文件行）、修复建议（一句话 + 代码片段或命令）。 |
| **R-SEC-02** | 不给总分、不给等级 A/B/C。只给**条数**与**分布**，按严重度排序。 |
| **R-SEC-03** | 密钥扫描只扫**本仓库快照**与 CI 产物清单，不扫运行中的环境变量内存。 |
| **R-SEC-04** | 检出疑似密钥时**不在页面展示原文**，只显示前后 4 字符与位置，避免二次泄露。 |
| **R-SEC-05** | 证书到期、密钥轮换类结论带时间维度，按"还剩几天"排序。 |
| **R-SEC-06** | 结论可豁免，豁免需 `reason` + `expiresAt`，到期自动复现。 |
| **R-SEC-07** | 与发布的联动：`HIGH` 以上未豁免结论会抬高该 stack 的发布风险评级（见 R-DEL-08）。 |
| **R-SEC-08** | 每次检查记录 `rule_version`，规则改进后历史结论标"旧规则"，不追改。 |
| **R-SEC-09** | 涉及账号 ID、分发 ID、桶名的字段：需登录可见，通知正文中一律脱敏（只留后 4 位）。 |

### 7.4 输出

| 端点 | 说明 |
| --- | --- |
| `GET /api/v1/security/findings?class=&severity=&state=` | 结论列表 |
| `GET /api/v1/security/findings/{id}` | 详情 + 证据 + 建议 |
| `POST /api/v1/security/findings/{id}/waive` | 豁免 |
| `GET /api/v1/security/summary` | 按类与严重度的分布 |

### 7.5 验收

- 4 类检查各至少命中 1 条真实结论（非空跑）。
- 密钥扫描在植入一个假 key 后 10 分钟内检出，页面不显示原文。
- 豁免到期后结论自动恢复 `OPEN`。

---

## 8. NOT · 通知 / Notification

### 8.1 职责

把平台内事件送到人手上，**并且不制造噪音**。

### 8.2 规则

| # | 规则 |
| --- | --- |
| **R-NOT-01** | 只有三类事件默认外发：`CRITICAL` 漂移、安全 `HIGH` 且与公网暴露相关、发布失败。其余默认为站内信。 |
| **R-NOT-02** | 同一 `(事件类型, 对象)` 在 60 分钟内只发一条，后续合并为"另有 N 条"。 |
| **R-NOT-03** | 每条通知必须有：结论一句话、影响对象、可点的平台链接、建议动作。缺任一项不发。 |
| **R-NOT-04** | 静默窗口：可按 stack 设维护窗口，窗口内不发外发通知，但站内信照记。 |
| **R-NOT-05** | 飞书卡片使用固定模板（`template: finding\|release\|cost\|gate`），不使用自由 markdown，避免版式漂移。 |
| **R-NOT-06** | 发送失败重试 3 次（1m/5m/15m），仍失败则写 `notify_dead`，并在平台顶部提示"通知通道异常"。 |
| **R-NOT-07** | 通知正文脱敏规则见 R-SEC-09；金额一律带口径与精度。 |

### 8.3 输出

| 端点 | 说明 |
| --- | --- |
| `GET /api/v1/notify/subscriptions` | 订阅配置 |
| `PUT /api/v1/notify/subscriptions/{id}` | 修改 |
| `POST /api/v1/notify/test` | 发一条测试卡片 |
| `GET /api/v1/notify/history` | 发送历史与状态 |

---

## 9. PLT · 平台自身 / Platform

### 9.1 身份与授权

| # | 规则 |
| --- | --- |
| **R-PLT-01** | 单租户。账号来源：本地账号（用户名 + 密码 hash），可选接 GitHub OIDC。不引入第三方 IdP。 |
| **R-PLT-02** | 两个角色：`OWNER`（全部读写 + 触发）、`VIEWER`（只读）。角色在配置文件中声明，不做复杂 RBAC。 |
| **R-PLT-03** | 会话 8 小时，敏感操作（dispatch、导出、改配置）需要二次确认，二次确认有效期 5 分钟。 |
| **R-PLT-04** | 所有状态变更操作写 `audit_log`：谁、何时、对什么、做了什么、结果。保留 12 个月。 |

### 9.2 云凭据

| # | 规则 |
| --- | --- |
| **R-PLT-05** | 三云凭据只存 Azure Key Vault，运行时通过托管身份读取，**不进环境变量、不进数据库、不进日志**。 |
| **R-PLT-06** | 每个云身份都是只读：AWS 只挂 `ViewOnlyAccess` + `Cost Explorer ReadOnly` + `ResourceExplorer ReadOnly`；GCP 只 `roles/viewer` + `roles/bigquery.dataViewer`；Azure 只 `Reader` + `Cost Management Reader`。 |
| **R-PLT-07** | 常规采集不使用 GitHub 令牌（走 `cicd/workflows.json` 快照）。唯一的长期令牌只用于 dispatch：对 `album-infra` 有 `Actions: write`、无 `contents` 权限，90 天轮换，到期前 14 天提醒。 |
| **R-PLT-08** | 凭据失效时，受影响模块降级为"上次快照"，页面顶部横幅说明哪个源失效。 |

### 9.3 任务与配置

| # | 规则 |
| --- | --- |
| **R-PLT-09** | 任务清单以代码声明（`JobRegistry`），不提供页面上的"新建任务"，避免配置漂移。 |
| **R-PLT-10** | 配置变更（阈值、忽略规则、预算、SLO）走 `config_version` 表，可回滚到任一历史版本。 |
| **R-PLT-11** | 平台自身有 `/health`（含依赖检查）与 `/metrics`（Prometheus 格式），供外部探针。 |

### 9.4 容灾矩阵与演练 / Failover matrix and drills

数据来自 `inventory`（当前资源）与人工录入（演练记录）。

| # | 规则 |
| --- | --- |
| **R-PLT-12** | 矩阵每行 = 一个组件，字段：主 / 备 / 切换方式 / RTO / RPO / 上次演练 / 状态 / 前置条件。 |
| **R-PLT-13** | `RTO`、`RPO` 只能是**已演练验证过**的数字；未演练的行显示「未验证」而不是理论值。 |
| **R-PLT-14** | 「上次演练」超过 90 天自动标红；超过 180 天该行 RTO/RPO 一并降级为「过期」。 |
| **R-PLT-15** | 每行必须列出**前置条件**（如"state 可读""凭据可用""DNS TTL 已过期"），前置条件缺失时切换被视为不可执行。 |
| **R-PLT-16** | 演练记录含：日期、范围、实际 RTO/RPO、遇到的问题、偏差原因、后续动作。一次演练可覆盖多行。 |
| **R-PLT-17** | 首期平台**不执行切换**，只提供 runbook 与记录；自动化切换不在范围内。 |

### 9.5 输出

| 端点 | 说明 |
| --- | --- |
| `POST /api/v1/auth/login` / `POST /api/v1/auth/logout` | 会话 |
| `GET /api/v1/platform/jobs` | 任务清单与最近运行 |
| `POST /api/v1/platform/jobs/{name}/run` | 手动触发一次采集（OWNER） |
| `GET /api/v1/platform/events/dead` | 事件死信 |
| `GET /api/v1/platform/config` / `PUT /api/v1/platform/config` | 配置与版本 |
| `GET /api/v1/platform/audit?from=&to=&actor=` | 审计 |
| `GET /api/v1/platform/dr/matrix` | 切换矩阵 |
| `GET /api/v1/platform/dr/drills` / `POST` | 演练记录 |

### 9.6 验收

- 用 VIEWER 账号调用 dispatch 返回 403 且有审计。
- 移除 Key Vault 读取权限后，平台启动成功但相关模块降级，`/health` 报 `DEGRADED` 而不是崩。
- 切换矩阵中至少 3 行有真实演练记录，其余标注「未验证」。

---

## 10. 规则索引 / Rule index

| 模块 | 规则数 | 前缀 |
| --- | --- | --- |
| INV | 14 | `R-INV-nn` |
| GRAPH | 10 | `R-GRP-nn` |
| DRIFT | 12 | `R-DRF-nn` |
| FIN | 13 | `R-FIN-nn` |
| DEL | 10 | `R-DEL-nn` |
| OBS | 7 | `R-OBS-nn` |
| SEC | 9 | `R-SEC-nn` |
| NOT | 7 | `R-NOT-nn` |
| PLT | 17 | `R-PLT-nn` |
| **合计** | **99** | — |

每条规则至少对应一个自动化测试；无测试的规则视为未实现。
