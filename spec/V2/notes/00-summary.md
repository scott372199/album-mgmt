# 00 · 架构总结 / Architecture Summary

| 项 / Item | 值 / Value |
| --- | --- |
| 状态 / Status | Approved for implementation |
| 用途 / Purpose | 十分钟读完这套架构；其余 8 份文档是它的展开 |
| 目标态 / Target state | 本文描述的是**目标架构**，与仓库现状的差异及改造步骤见 `08-execution-plan.md` |

---

## 1. 一句话

把三个云、一个 CI 系统、九份 Terraform state 里的事实收进一个只读的决策台：**资源按「项目 → 层」串成图，成本按 FOCUS 归一后分摊到图上的节点，漂移和发布挂在同一个节点上。**

*One read-only decision surface that turns three clouds and nine Terraform states into a single graph of (project, layer), with cost, drift and releases attached to the same nodes.*

---

## 2. 七个判断 / Seven judgements

这七条决定了后面所有的设计。每一条都可以单独被质疑，但它们互相支撑。

| # | 判断 | 一句话理由 |
| --- | --- | --- |
| **J1** | 层号 **就是** apply 顺序：`00-bootstrap → 01-storage → 02-data → 03-compute → 04-edge` | 只要有一条反向依赖，"层号"就失去意义；把数据层放在计算层之前、边缘层放在计算层之后，反向边归零 |
| **J2** | 图谱节点是 `Stack = (项目, 层)`，不引入"服务"抽象 | 目录结构 + 依赖顺序已经是一份拓扑声明，再加一层抽象必然失真 |
| **J3** | 拓扑来自结构，不来自猜测：结构/`remote_state` 权重 1.0，标签推断只有 0.70 且默认不画 | 用户必须能区分"代码写死的依赖"和"名字看起来像" |
| **J4** | 平台只读云、只触发 CI，永不执行 apply | 写操作的唯一入口是 CI；平台一旦有写权限就成了最高权限单点 |
| **J5** | 先归一（FOCUS 1.x）再分摊，三口径（Billed / Amortized / Allocated）同时存在 | 财务看发票、决策看摊销，砍掉任一个都会导致数字对不上；未分配金额单独成行，禁止摊平 |
| **J6** | 控制平面（平台、state、凭据、权威 DNS）与数据平面不在同一朵云 | AWS 挂掉时必须有指挥台能 `terraform plan`，否则连"恢复 AWS"都做不到 |
| **J7** | 容灾矩阵的 RTO/RPO 只认演练实测值，平台不执行切换 | 理论值在真故障时比"不知道"更危险；误判的自动切换损失通常大于多停几分钟 |

---

## 3. 三个平面 / Three planes

![三平面与容灾](diagrams/08-multi-plane-dr.svg)

| 平面 | 承接 | 云 | 职责 |
| --- | --- | --- | --- |
| **DATA** | 业务站点、备份/双活栈 | AWS（主）+ GCP（备/双活） | 跑业务流量 |
| **CONTROL** | 平台本体、PostgreSQL、state、Key Vault、权威 DNS | Azure | 决策所需的事实与凭据 |
| **OBS** | Grafana / Loki / Prometheus / Tempo | AWS `sites/management`（开关门控） | 观测数据源，平台只读聚合 |

六项资产必须迁出数据云，否则 CONTROL 平面是假的：

| 资产 | 现在在哪 | 目标位置 |
| --- | --- | --- |
| Terraform state | S3 | Azure Blob + lease |
| 云凭据 | AWS Secrets Manager | Azure Key Vault |
| 权威 DNS | Route 53 | Azure DNS |
| 控制台 | 未建 | Azure Container Apps |
| 平台数据库 | 未建 | Azure PostgreSQL |
| 制品/镜像 | 各云 ECR | 管理面 registry（数据面只读） |

---

## 4. 站点与层 / Sites and layers

**每个站点五层同构，层号即执行顺序。** 这一条把"层号"和"执行顺序"重新绑在一起，是全套设计的地基。

```
s00-account                    ← 共享身份层，顺序 0，永不随站点销毁
   │
   ├── sites/photos            00-bootstrap → 01-storage → 02-data → 03-compute → 04-edge
   ├── sites/management        同上（mgmt_enabled 门控）
   └── sites/platform          同上（平台自身）
```

| 层 | 顺序 | kind | 管什么 | 为什么在这个位置 |
| --- | --- | --- | --- | --- |
| `s00-account` | 0 | account | state 容器、跨云 OIDC、infra 角色、KMS、日志归档 | 所有站点只读它的输出 |
| `00-bootstrap` | 1 | bootstrap | 站点角色、镜像仓库、密钥容器 | 后面每层都要用这些身份 |
| `01-storage` | 2 | storage | 对象存储、生命周期、CORS | 无下游依赖，最早能建 |
| `02-data` | 3 | data | 数据库、队列、KV、参数 | **必须在计算层之前**：计算层要注入端点 |
| `03-compute` | 4 | compute | 函数、容器、API、负载均衡 | 读数据层端点，产出入口地址 |
| `04-edge` | 5 | edge | CDN、证书、WAF | **必须在计算层之后**：origin 指向计算层 |
| `05-observe` | 6 | observe | 日志组、告警、仪表盘 | 消费以上所有层的产物；可按需不建 |

三条由此推出的不变式（`make check` 强制）：

1. 层号连续且从 0 开始，唯一，无空洞。
2. 站点内只能依赖**更小层号**的层；跨站点只能读 `s00-account`。
3. 引用上游 output 的层，其层号必须大于被引用层。

> 顺序唯一之后，就不再需要"每站一份 apply 顺序清单"这种东西。management 的 edge 也自然在最后，因为 04 > 03。

---

## 5. 多云统一模型 / Unified multi-cloud model

![多云统一模型](diagrams/06-multicloud-model.svg)

只在四个地方做归一，其余字段一律保留云的原始写法。

| 归一点 | 做法 |
| --- | --- |
| **资源域** | `scope_node` LTREE 树：`cloud → 账号/订阅/项目 → 站点 → 层`，可嵌套 |
| **资源类型** | `canonical_type`（如 `storage.object`），由 `type_mapping` 数据表映射，未命中记 `unknown` 不猜 |
| **标签语义** | 6 个规范键 `app / site / layer / env / owner / cost-center`，键名比较统一小写，展示保留原始写法 |
| **成本** | FOCUS 1.x 行模型，账期统一 UTC 月初 |

三云的硬差异写死在采集器里，不靠"约定"：

| 差异 | 处理 |
| --- | --- |
| GCP 标签键强制小写、不允许 `-` | `cost-center` ↔ `cost_center` 双向映射 |
| Azure tag key 大小写不敏感但保留原样 | 比较转小写，展示留原文 |
| Azure 多一层 Resource Group | `subscription/rg` 两级建节点，层挂到 `rg` 下 |
| 三云 region 名不同名同地 | 统一到 metro（`singapore` = `ap-southeast-1` = `asia-southeast1`） |

**能力矩阵显式声明差异**，不假设三云等价：能力缺失时走降级路径，并把"降级中"显示在数据源健康页。

---

## 6. 栈图谱 / Stack graph

![栈图谱三视图](diagrams/07-service-graph.svg)

节点 = `(站点, 层)`，16 个。边按**来源**赋权，权重即可信度，且必须携带证据文本：

| 级别 | 来源 | 权重 | 举例 |
| --- | --- | --- | --- |
| 1 | 栈结构（层号相邻且声明依赖） | 1.00 | `01-storage → 03-compute` |
| 2 | `terraform_remote_state` | 1.00 | `04-edge` 读 `03-compute` 的 ingress |
| 3 | 资源引用（ARN / 域名 / ID 出现在对方配置） | 0.95 | CDN origin 指向 ALB 域名 |
| 4 | 配置引用（环境变量、参数路径） | 0.90 | 函数 env 里的表名 |
| 5 | 观测到的流量 | 0.85 | CDN → origin 请求 |
| 6 | 仅标签相同 | 0.70 | **默认折叠**，需手动展开 |

三个视图：**分层**（怎么搭起来）、**数据流**（一个请求经过哪些层）、**成本**（钱花在哪个环）。三视图共用同一节点集合，只换布局与可见边。

---

## 7. 成本 / Cost

![成本管道](diagrams/03-cost-pipeline.svg)

```
账单原始行  ──S0 接入──▶  ──S1 FOCUS 归一──▶  ──S2 三口径──▶  ──S3 分摊──▶  ──S4 差异桥──▶
raw_cost_line            cost_fact             BILLED                       allocation       cost_bridge
                                               AMORTIZED
                                               ALLOCATED
```

四条不许退让的规定：

| # | 规定 |
| --- | --- |
| 1 | 任何金额必须带 `basis`（口径）+ `precision`（精度）+ `currency`，缺一即契约违规 |
| 2 | 分摊四步固定不可跳：资源直挂 → 规范标签 → scope 推断 → 未分配 |
| 3 | 未分配金额单独成行，**禁止**摊进任何站点来"抹平"，目标 < 2% |
| 4 | 差异桥五类（共享成本 / 承诺未摊销 / 跨期调整 / 税费 / 汇率）之和必须等于 `BILLED − ALLOCATED`，误差 0 |

---

## 8. 漂移 / Drift

![漂移双通道](diagrams/04-drift-detection.svg)

| 通道 | 机制 | 频率 | 能发现 |
| --- | --- | --- | --- |
| **A** | 云 API 实时属性 vs 代码快照 | 每日 | 属性被改：标签、策略、加密、生命周期、超时、内存 |
| **B** | `terraform plan -refresh-only` 由 CI 产出 | 每周 | 代码与 state 的关系问题：漏提交、手改后代码没跟上、state 丢失 |

最小单位是 `(资源, 属性)`，不是资源——只有到属性才判得出轻重。严重度只看两维：属性敏感性 × 是否影响可用性，四档。

**通道 B 失败必须记 `INCONCLUSIVE`，不得记为"0 条漂移"。** "未能判断"和"没有问题"混同，是这类系统最常见的谎。

---

## 9. 平台边界 / Platform boundary

| 做 | 不做 |
| --- | --- |
| 展示事实、给证据、算分摊、评风险 | 写云资源、执行 apply、改 DNS、执行容灾切换 |
| 触发一次 CI workflow（双人确认） | 持有提升权限、自动修复、自动切换 |
| 存 24 小时指标快照 | 存日志、做告警引擎、做 IaC 编辑器 |
| 单租户、两角色（OWNER / VIEWER） | 多租户、复杂 RBAC、对外 SaaS |

---

## 10. 从现状到目标 / From today to target

目标态与当前仓库的差异只有四类，执行计划在 `08`：

| # | 差异 | 处理 |
| --- | --- | --- |
| E1 | 层目录是 `00/10/20/30` 且两站顺序不一致 | 重排为 `00/01/02/03/04`，新增 `02-data` 把数据类资源从计算层拆出 |
| E2 | state 在 S3 | 全部迁到 Azure Blob + lease；**不迁移 state，销毁重建** |
| E3 | 平台不存在 | 新建 `sites/platform` 五个层 |
| E4 | 权威 DNS 在 Route 53 | 迁 Azure DNS，切换走 DNS failover |

重建的四条前提（缺一条就不许动）：① 没有 `prevent_destroy` 锁死 ② 数据有去处（快照或导出）③ 允许停机 ④ 认下 DNS 与外部端点会变。

---

## 11. 数字索引 / By the numbers

| 项 | 数量 | 出处 |
| --- | --- | --- |
| 领域模块 | 9 | `02` |
| 领域规则 `R-xxx-nn` | 99 | `02` §10 |
| 数据库表 / 视图 / 索引 | 44 / 6 / 45 | `03` |
| API 端点 | 57 | `04` §13 |
| 页面 | 9 | `05` |
| 验收断言 | 45 | `06` §2 |
| 决策记录 | 42 | `07` |
| 站点 / 层（目标态） | 4 / 16 | §4 |
| 图谱节点（首批） | 16 | §6 |
