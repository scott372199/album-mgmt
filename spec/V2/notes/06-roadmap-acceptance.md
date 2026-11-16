# 06 · 路线图与验收 / Roadmap and Acceptance

| 项 / Item | 值 / Value |
| --- | --- |
| 状态 / Status | Approved for implementation |
| 依赖 / Depends on | `02`（规则）、`05`（页面）、`07`（决策） |
| 节奏 / Cadence | 5 个阶段，每阶段独立上线；没有"全部做完才有用"的设计 |

![路线图](diagrams/05-roadmap.svg)

---

## 1. 阶段划分 / Phases

| 阶段 | 名称 | 目标一句话 | 出口判据 |
| --- | --- | --- | --- |
| **S0** | 骨架可跑 | 一个进程起得来，库表建得对，栈结构能读 | 本地 `docker compose up` + 5 个页面骨架 |
| **S1** | 目录与图谱 | 三云资源能进来，栈图谱能画对 | 资源误差 < 1%，图谱 ≥ 12 条真实边 |
| **S2** | 成本 | 三口径与分摊能出数，差异能解释 | 三云总额误差 < 1%，未分配 < 2% |
| **S3** | 治理 | 漂移、发布、安全三条线跑通 | 漂移误报 < 5%，apply 5 分钟内可见 |
| **S4** | 容灾与稳态 | 矩阵有演练记录，平台自身可降级 | 3 行已验证，断源演练通过 |

### S0 · 骨架可跑

| 交付 / Deliverable | 内容 |
| --- | --- |
| 后端骨架 | Spring Boot 3.3 + Modulith，8 个模块空壳，`ApplicationModules.verify()` 通过 |
| 数据层 | Flyway `V1__core.sql` 全量建表成功，44 表 6 视图 |
| 前端骨架 | 9 个路由 + 全局壳 + 设计 token |
| 部署 | `sites/platform` 5 层 IaC 可 plan，`site.yaml` 通过 `make check` |
| 依赖 | `docker compose` 起 PG16（开发）；无云凭据用 `--fixture` 模式 |

### S1 · 目录与图谱

| 交付 | 内容 |
| --- | --- |
| 采集器 | AWS/GCP/Azure 三云只读采集，`--fixture` 可回放 |
| 归一 | `type_mapping` 42 类、6 个规范标签、metro 表 |
| 栈图谱 | 三视图 + 六级边 + 影响面 |
| 前置动作 | 开启 AWS Resource Explorer 并等索引完成；开启 GCP Cloud Asset Inventory |

### S2 · 成本

| 交付 | 内容 |
| --- | --- |
| S0–S1 | CUR 导出 / GCP Billing Export / Azure Cost Export 接入（缺失时降级 L0 汇总） |
| S2 三口径 | BILLED / AMORTIZED / ALLOCATED |
| S3 分摊 | 四步分摊 + 规则版本化 |
| S4 差异桥 | 五类差额，`reconciles` 必须为 true |
| 页面 | 成本页全部区块 + 导出 |

### S3 · 治理

| 交付 | 内容 |
| --- | --- |
| 漂移通道 A | 每日属性对账 + 四档严重度 + 认领/忽略 |
| 漂移通道 B | CI `iac-snapshot.yml` + 周频 `plan -refresh-only` |
| 发布链路 | run 采集、资源变更差集、风险评级、三步 dispatch |
| 安全 | 四类检查 + 豁免 |
| 通知 | 飞书卡片 + 静默 + 去重 |

### S4 · 容灾与稳态

| 交付 | 内容 |
| --- | --- |
| 切换矩阵 | 8 个组件，前置条件，RTO/RPO 只认演练值 |
| 演练记录 | 表单 + 时间线 + 自动更新矩阵 |
| 降级演练 | 断 AWS / 断 GCP / 断 GitHub 三种，页面仍可读 |
| 稳态 | 备份、告警、Runbook、季度复盘 |

---

## 2. 验收断言 / Acceptance assertions

每条都必须是**可执行脚本或可复现步骤**。"人工确认"类断言必须写明样本量与判定人。

### 2.1 S0

| # | 断言 | 方式 |
| --- | --- | --- |
| A0-1 | `mvn verify` 通过，`ApplicationModules.verify()` 无违规 | CI |
| A0-2 | 空库执行 `flyway migrate` 成功；重复执行幂等 | CI |
| A0-3 | 故意让 `inventory` 依赖 `finops`，构建失败 | CI（预期失败用例） |
| A0-4 | `docker compose up` 后 `/health` 返回 `UP`，`/api/v1/auth/me` 返回 401 | 脚本 |
| A0-5 | `make check` 对 `sites/platform/site.yaml` 的 6 条断言全通过 | CI |
| A0-6 | 前端 9 个路由可访问，无 404 请求 | Playwright |

### 2.2 S1

| # | 断言 | 方式 |
| --- | --- | --- |
| A1-1 | 三云资源总数与各自控制台计数误差 < 1% | 脚本比对 + 人工记录 |
| A1-2 | 随机 20 个资源，`scope_path` 与 `layer_id` 全部正确 | 人工，记录在 `docs/acceptance/S1.md` |
| A1-3 | `unassigned` 占比 < 3% | `GET /inventory/health` |
| A1-4 | 图谱节点 16 个，`STACK_STRUCTURE` + `TF_REMOTE_STATE` 边 ≥ 12 条 | `GET /graph` |
| A1-5 | 抽 5 条边的 `evidence` 能在代码或 `state.json` 找到原文 | 人工 |
| A1-6 | 篡改一个资源标签，16 分钟内反映到目录页 | 脚本 + 计时 |
| A1-7 | 三个站点的层号都读为 `00..04` 连续；`02-data` 在 `03-compute` 之前、`04-edge` 在最后 | 单测 |
| A1-8 | 删掉一个层的 `site.yaml` 条目，该站点标 `valid=false` 且不猜测 | 单测 |

### 2.3 S2

| # | 断言 | 方式 |
| --- | --- | --- |
| A2-1 | 三云月度总额与账单误差 < 1%（L1 明细，连续 3 个月） | 脚本 + 财务核对 |
| A2-2 | 差异桥五类之和 == `BILLED - ALLOCATED`，误差 0 | 单测（定点数） |
| A2-3 | 未分配比例 < 2% | `GET /cost/summary` |
| A2-4 | 任一 stack 月成本可展开到成本行 | 人工 5 个样本 |
| A2-5 | 只有 L0 数据时，页面标注"服务级估算"且不参与 stack 排名 | 单测 + 人工 |
| A2-6 | 缺少汇率的行不参与折算，且被计入"未知"计数 | 单测 |
| A2-7 | 分摊重建后历史查询仍按旧 `rule_version` 复现 | 单测 |

### 2.4 S3

| # | 断言 | 方式 |
| --- | --- | --- |
| A3-1 | 改 S3 生命周期 → 次日通道 A 报 `HIGH`，expected/actual 正确 | 演练 |
| A3-2 | 改 Lambda 内存 → 通道 A 报 `HIGH`，同周通道 B 报 `CODE_BEHIND` | 演练 |
| A3-3 | 漂移误报率 < 5%（抽 50 条，人工判定） | 人工 |
| A3-4 | 通道 B 权限不足时 `INCONCLUSIVE`，页面不显示 0 条 | 演练 |
| A3-5 | apply run 结束后 5 分钟内出现在平台，变更集合与 terraform 输出一致 | 计时 + 比对 |
| A3-6 | 同一用户执行 confirm 返回 403 | 单测 |
| A3-7 | `precheck.allowed=false` 时提交返回 422 并透出 blockers | 单测 |
| A3-8 | 植入假密钥 10 分钟内检出，页面不含原文 | 演练 |
| A3-9 | 同一告警 60 分钟内只发一条外发通知 | 单测 |

### 2.5 S4

| # | 断言 | 方式 |
| --- | --- | --- |
| A4-1 | 切换矩阵 ≥ 3 行 `verified=true`，其余显示"未验证" | 人工 |
| A4-2 | 演练记录后矩阵 RTO 更新为实测值 | 单测 |
| A4-3 | 断 AWS：总览/目录/成本页仍可读，横幅点名 AWS，其余区块正常 | 演练 |
| A4-4 | 断 GCP：成本页显示"不含 GCP"，差异桥仍 reconciles | 演练 |
| A4-5 | 断 GitHub：发布页显示上次成功时间，不显示"最新" | 演练 |
| A4-6 | 撤销 Key Vault 权限：进程启动成功，`/health` 返回 `DEGRADED` | 演练 |
| A4-7 | 99 条规则中，每条至少一个自动化测试 | CI 覆盖报告 |

### 2.6 四条全局成功标准（交付总验收）

| # | 标准 | 判定 |
| --- | --- | --- |
| SC-1 | 任意资源三次点击内回答四问 | 10 个随机样本走查 |
| SC-2 | 成本 T+2 内可查，未分配 < 2% | 连续 3 个账期 |
| SC-3 | 漂移覆盖 100% 受管资源，误报 < 5% | 抽样 |
| SC-4 | apply 5 分钟内可见 | 计时 |

---

## 3. 测试策略 / Test strategy

| 层 / Layer | 内容 | 门槛 |
| --- | --- | --- |
| 单元测试 | 每条 `R-*` 规则一个测试，命名 `r_inv_05_*` | 规则覆盖率 100% |
| 模块测试 | `ApplicationModules.verify()` + ArchUnit 边界规则 | 必须通过 |
| 契约测试 | OpenAPI 快照、金额四元组、降级响应、角色矩阵 | 必须通过 |
| 集成测试 | Testcontainers PG16 + 三云 fixture + GitHub fixture | 必须通过 |
| E2E | Playwright 覆盖 9 页 × 4 状态 | 必须通过 |
| 几何校验 | 图谱连线不穿框、节点不重叠、文字不溢出 | 必须通过 |
| 性能 | k6：目录 P95、图谱首屏、成本查询 | 达到 `01` §10 |
| 演练 | 断源三种 + 漂移两通道 + 双人确认 | 记录在 `docs/acceptance/` |

**离线优先**：所有三云与 GitHub 调用必须有 fixture 回放，CI 不依赖真实云凭据。

---

## 4. 交付前检查清单 / Definition of done

每个阶段结束前逐条勾选，缺一不可合并。

| # | 检查项 |
| --- | --- |
| D-1 | 该阶段所有 `R-*` 规则有测试且通过 |
| D-2 | 新增端点已登记到 `04` §13 并在 `05` 页面数据来源表出现 |
| D-3 | 新增页面区块实现四种状态 |
| D-4 | 所有金额带口径与精度 |
| D-5 | 所有结论可点开到证据 |
| D-6 | 降级路径演练过一次并留记录 |
| D-7 | `make check` + `terraform validate` + `make fmt-check` 全绿 |
| D-8 | `spec/V2` 中受影响的章节已同步（文档不是一次性的） |
| D-9 | 审计日志覆盖新增的写操作 |
| D-10 | Runbook 更新（触发、降级、恢复三类） |

---

## 5. Runbook 清单 / Runbook index

平台上线必须配套以下 6 份操作手册，放在 `docs/runbook/`。

| # | Runbook | 触发场景 | 关键步骤 |
| --- | --- | --- | --- |
| R-1 | 首次接入新账号 | 新增 AWS 账号或 GCP 项目 | 只读角色 → 验证清单 API → 跑一次采集 → 核对计数 |
| R-2 | 开启/关闭 management 站 | `mgmt_enabled` 变更 | 改两层 tfvars → compute → edge 顺序 apply → 校验资源存在性 → 平台 gates 页确认 `CONSISTENT` |
| R-3 | 触发一次 apply | 需要变更基础设施 | precheck → 提交 → 他人确认 → 观察 run → 核对 `resource_change` |
| R-4 | 漂移处置 | `CRITICAL` 漂移 | 判定 CODE_BEHIND/UNCOMMITTED/STATE_DRIFT → 改代码或改云端 → 复核消失 |
| R-5 | 数据源失效 | 采集连续失败 | 看 `job_run` 错误 → 查凭据/配额/索引 → 修好后手动触发 → 确认 `asOf` 前进 |
| R-6 | 平台自身故障 | `/health` 非 UP | 看容器日志 → 查 PG 连接 → 查 Key Vault 权限 → 恢复后重建派生表 |

---

## 6. 风险与缓解 / Risks

| # | 风险 | 概率 | 影响 | 缓解 |
| --- | --- | --- | --- | --- |
| RK-1 | 三云账单导出迟迟配不起来，S2 停在 L0 | 高 | 成本页价值下降 | S0 汇总先上线并明确标注"估算"，S2 的验收允许 L0 + 部分 L1 |
| RK-2 | Resource Explorer 索引慢/不全 | 中 | 目录不全 | 降级为按区 describe 循环，接受更慢（15 min 内完成即可） |
| RK-3 | 单人维护，半年后腐化 | 高 | 平台停摆 | 规则 100% 测试 + `make check` 覆盖 spec 的"必须"条目 + 每阶段独立可用 |
| RK-4 | 分摊口径被质疑 | 中 | 失去信任 | 差异桥强制展示、规则版本化、每个数字可钻到成本行 |
| RK-5 | 平台自身成为新的单点 | 中 | 决策中断 | CONTROL 在 Azure；降级模式可读上次快照；RTO 4h |
| RK-6 | Azure 不熟悉导致平台运维困难 | 中 | 上线延迟 | 平台仅 5 层，全用官方模块，不使用 Azure 独有深水区产品 |

---

## 7. 追溯矩阵 / Traceability

| 需求域 | 规则 | 端点 | 页面 | 验收 |
| --- | --- | --- | --- | --- |
| INV | R-INV-01..14 | `/inventory/*` | 2.2 | A1-1..A1-8 |
| GRAPH | R-GRP-01..10 | `/graph/*` | 2.3 | A1-4..A1-5、FE-6 |
| DRIFT | R-DRF-01..12 | `/drift/*` | 2.6 | A3-1..A3-4 |
| FIN | R-FIN-01..13 | `/cost/*` | 2.4 | A2-1..A2-7 |
| DEL | R-DEL-01..10 | `/delivery/*` | 2.5 | A3-5..A3-7 |
| OBS | R-OBS-01..07 | `/observe/*` | 2.9 | A4-3..A4-5 |
| SEC | R-SEC-01..09 | `/security/*` | 2.7 | A3-8 |
| NOT | R-NOT-01..07 | `/notify/*` | 全局 | A3-9 |
| PLT | R-PLT-01..17 | `/platform/*` | 2.8、全局 | A0-4..A0-6、A4-1..A4-6 |
