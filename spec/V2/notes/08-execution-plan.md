# 08 · 执行计划 / Execution Plan

| 项 / Item | 值 / Value |
| --- | --- |
| 状态 / Status | Approved for execution |
| 目标 / Goal | 把 `album-infra` 从当前布局改造成 `00`–`07` 描述的目标态 |
| 前置 / Prerequisite | `00-summary` 已读；`README` §3 的六条差异已确认 |
| 停机 / Downtime | **计划内零停机**（唯一例外是 E4 的 DNS TTL 等待期） |

---

## 0. 四条前置条件 / Four preconditions

动手前逐条核对，缺一条就停。

| # | 条件 | 怎么验 |
| --- | --- | --- |
| P1 | 没有 `prevent_destroy` 锁死需要动的资源 | `grep -rn "prevent_destroy" sites 00-account` 结果为空 |
| P2 | 有状态数据有去处 | `photos` 站点桶版本控制开启 + 一次跨区复制或本地快照；DynamoDB 开启 PITR 或导出到 S3 |
| P3 | 停机窗口已获批（仅 E4 需要） | 写在变更单里：DNS TTL 从 3600 降到 60 后满 1 小时才能切 |
| P4 | 接受 DNS 与外部端点可能变化 | 站点域名 `photos.chenyouyi.top` 不变，但分发 ID / 桶名会变；任何人写死过分发 ID 都要一起改 |

**一条原则贯穿全程**：**有数据的资源只认领、不重建。** 其余资源的 state 换 key，一条 `terraform init -migrate-state` 就能做到零差异——没有任何理由为此付出停机。

*Resources holding data are adopted, never recreated. For everything else a state-key change is one `init -migrate-state` away, so downtime buys nothing.*

---

## 1. 六个批次 / Six batches

| 批次 | 名称 | 会不会碰云端资源 | 预计 | 可回滚 |
| --- | --- | --- | --- | --- |
| **E0** | Azure 底座与凭据 | 只新建，不改现有 | 1 天 | 是（删资源组） |
| **E1** | 目录与清单重排 | **不碰** | 0.5 天 | 是（`git revert`） |
| **E2** | state 逐层迁到 Blob | 不碰资源，只搬 state | 1 天 | 是（state 有双份） |
| **E3** | 拆出 `02-data` | 动资源归属 | 1 天 | 是（`state mv` 反向） |
| **E4** | 权威 DNS 迁 Azure | 动解析 | 0.5 天 + TTL 等待 | 是（改回 NS） |
| **E5** | 平台站点落地 | 只新建 | 2 天 | 是（整站销毁） |
| **E6** | 收尾与冻结 | 删除旧后端 | 0.5 天 | 否（此后以 V2 为基线） |

顺序不可调换：**E0 → E1 → E2 → E3/E4 可并行 → E5 → E6**。

---

## 2. E0 · Azure 底座与凭据

### 2.1 建 state 容器（一次，手工）

```bash
RG=album-tfstate-rg; SA=albumtfstate; LOC=southeastasia
az group create -n $RG -l $LOC
az storage account create -n $SA -g $RG -l $LOC \
  --sku Standard_GRS --kind StorageV2 --min-tls-version TLS1_2 \
  --allow-blob-public-access false --encryption-services blob
az storage container create -n tfstate --account-name $SA --auth-mode login
# 版本控制与软删：state 被误删/误改时可回到上一版
az storage account blob-service-properties update -n $SA -g $RG \
  --enable-versioning true --enable-delete-retention true --delete-retention-days 30
# lease 锁不需要额外配置，Terraform 用 native lease
```

### 2.2 CI 的 Azure 身份（OIDC 联邦，不落地密钥）

```bash
APP=$(az ad app create --display-name album-infra-tf --query appId -o tsv)
az ad sp create --id $APP
az role assignment create --assignee $APP --role "Storage Blob Data Contributor" \
  --scope $(az storage account show -n $SA -g $RG --query id -o tsv)

# 只允许 album-infra 仓库的 main 分支与 production 环境换取令牌
az ad app federated-credential create --id $APP --parameters '{
  "name":"gha-main","issuer":"https://token.actions.githubusercontent.com",
  "subject":"repo:scott372199/album-infra:ref:refs/heads/main",
  "audiences":["api://AzureADTokenExchange"]}'
```

GitHub 仓库新增 **Variables**（不是 Secrets）：

| 名称 | 值 |
| --- | --- |
| `AZURE_CLIENT_ID` | 上面 `$APP` |
| `AZURE_TENANT_ID` | `az account show --query tenantId -o tsv` |
| `AZURE_SUBSCRIPTION_ID` | 同上 |
| `TF_STATE_STORAGE_ACCOUNT` | `albumtfstate` |
| `TF_STATE_CONTAINER` | `tfstate` |

### 2.3 验证

```bash
az storage blob list --account-name albumtfstate -c tfstate --auth-mode login -o table
# 期望：空列表，无报错
```

---

## 3. E1 · 目录与清单重排

**这一批不产生任何云端变更**，纯代码。评审通过后再进 E2。

### 3.1 目录重命名

```bash
cd album-infra
# 共享层
git mv 00-account s00-account

# photos：20-edge → 04-edge, 30-compute → 03-compute, 10-storage → 01-storage, 00-bootstrap 不动
git mv sites/photos/10-storage sites/photos/01-storage
git mv sites/photos/20-edge    sites/photos/04-edge
git mv sites/photos/30-compute sites/photos/03-compute

# management：同样四步（30-compute 先改 03，20-edge 改 04）
git mv sites/management/10-storage sites/management/01-storage
git mv sites/management/20-edge    sites/management/04-edge
git mv sites/management/30-compute sites/management/03-compute
```

> 改名顺序必须是"先腾位置再落户"：`20-edge → 04-edge` 要先于任何占用 `02`/`03` 的操作。用上面这个顺序即可。

### 3.2 新增 `02-data`

两层都从计算层拆出数据类资源（DynamoDB / SQS / SSM 参数）。

```bash
mkdir -p sites/photos/02-data sites/management/02-data
# 每层复制骨架：versions.tf / variables.tf / outputs.tf / backend.hcl / prod.tfvars
```

`photos/02-data/main.tf` 至少承接（从原 `30-compute` 移出）：

| 资源 | 原地址 | 新地址 |
| --- | --- | --- |
| 会话表 | `aws_dynamodb_table.sessions` | `aws_dynamodb_table.sessions`（同地址，换 state） |
| 任务队列 | `aws_sqs_queue.jobs` + DLQ | 同 |
| SSM 参数 | `module.secure_params.*` | 同 |

`management/02-data` 可以为空层（只有 `versions.tf` + outputs），保持同构——**空层也要存在**，否则 CI 矩阵与图谱规则要为它写分支。

### 3.3 新建 `sites/platform`

五层骨架，`apply` 内容见 `01` §11.2。E1 阶段只放骨架与 `site.yaml`，实际资源在 E5 建。

### 3.4 每层 `backend.hcl`

旧：

```hcl
bucket       = "chenyouyi-tfstate"
key          = "photos/10-storage/terraform.tfstate"
region       = "ap-southeast-1"
use_lockfile = true
encrypt      = true
```

新：

```hcl
storage_account_name = "albumtfstate"
container_name       = "tfstate"
key                  = "photos/01-storage.tfstate"
use_azuread_auth     = true
```

### 3.5 `site.yaml` 与 `make check`

按 `README` §3.2 写四份 `site.yaml`，并在 `scripts/check.mjs` 增加六条断言（A1–A6）。

`Makefile` 改动两处：

```make
PHOTOS_LAYERS     := 00-bootstrap 01-storage 02-data 03-compute 04-edge
MANAGEMENT_LAYERS := 00-bootstrap 01-storage 02-data 03-compute 04-edge
PLATFORM_LAYERS   := 00-bootstrap 01-storage 02-data 03-compute 04-edge
```

`layer_dir` 与 `init` 的目标从 `00-account` 改为 `s00-account`。

### 3.6 验证（纯离线）

```bash
make check                       # 91 项既有断言 + 6 条新断言全过
make fmt-check
for l in 00-bootstrap 01-storage 02-data 03-compute 04-edge; do
  (cd sites/photos/$l && terraform init -backend=false -input=false >/dev/null && terraform validate)
done
```

### 3.7 回滚

```bash
git revert <E1 的 merge commit>   # 纯目录与配置文件，回滚后与执行前完全一致
```

---

## 4. E2 · state 逐层迁到 Blob

**每层独立做、独立验证、独立回滚。** 一层大约 5 分钟。

### 4.1 单层流程（以 `photos/01-storage` 为例）

```bash
cd sites/photos/01-storage

# 1. 先备份旧 state（S3）
aws s3 cp s3://chenyouyi-tfstate/photos/10-storage/terraform.tfstate \
          ./terraform.tfstate.s3.bak

# 2. 换后端并把 state 搬过去（-migrate-state 会拷贝而不是重放）
terraform init -migrate-state -input=false -backend-config=backend.hcl

# 3. 必须零差异
terraform plan -input=false -var-file=prod.tfvars -detailed-exitcode
echo "exit=$?   # 期望 0"

# 4. 确认 Blob 上有对象
az storage blob exists --account-name albumtfstate -c tfstate \
  --name photos/01-storage.tfstate --auth-mode login
```

### 4.2 顺序

按层号从小到大，逐站完成：

| 顺序 | scope | 风险点 |
| --- | --- | --- |
| 1 | `s00-account` | 它自己创建 state 容器，**本层用本地 state 引导**：本地 apply → `terraform state push` 到 Blob → 再启用 backend |
| 2–6 | `photos/00…04` | `01-storage` 最需要盯：桶里有数据，plan 必须为零 |
| 7–11 | `management/00…04` | 管理站当前 `mgmt_enabled=false`，先保持关闭做迁移，迁移完再开 |
| 12–16 | `platform/00…04` | 空 state 起步，无迁移动作 |

### 4.3 验证

```bash
# 每层都跑一遍：期望全部 "无变更"
make plan-all

# state 数量核对：Blob 上应有 16 个对象
az storage blob list --account-name albumtfstate -c tfstate \
  --auth-mode login --query "length(@)" -o tsv
```

### 4.4 回滚

单层回滚：把 `backend.hcl` 换回 S3 版本，`terraform init -migrate-state` 再搬回去。旧 S3 state 在 E6 之前**不删**。

---

## 5. E3 · 拆出 `02-data`

E2 之后，DynamoDB / SQS / SSM 参数仍写在 `03-compute` 的 state 里。有两种走法：

### 5.1 默认：`state mv`（零停机，推荐）

```bash
cd sites/photos/02-data
terraform init -input=false -backend-config=backend.hcl

# 从 03-compute 的 state 里把地址搬过来（先 pull 旧 state 到本地文件）
terraform state pull > /tmp/compute.tfstate        # 在 03-compute 目录执行
cd ../02-data
terraform state push /tmp/compute.tfstate          # 临时，仅用于 mv 的源
# 更稳的做法：直接用 03-compute 目录做源，逐条 mv 到 02-data 的同名地址
```

实务上更简单：把资源块从 `03-compute/main.tf` **剪切**到 `02-data/main.tf`，然后在两个目录分别执行：

```bash
# 03-compute 里：把地址从本 state 移除（不删资源）
terraform state rm aws_dynamodb_table.sessions aws_sqs_queue.jobs aws_sqs_queue.jobs_dlq

# 02-data 里：把线上资源认领进来（不重建）
terraform import aws_dynamodb_table.sessions album-sessions
terraform import aws_sqs_queue.jobs  https://sqs.ap-southeast-1.amazonaws.com/156487532333/album-jobs
```

`03-compute` 侧改为读 `02-data` 的 output：

```hcl
data "terraform_remote_state" "data" {
  backend = "azurerm"
  config = {
    storage_account_name = "albumtfstate"
    container_name       = "tfstate"
    key                  = "photos/02-data.tfstate"
    use_azuread_auth     = true
  }
}
```

### 5.2 可选：销毁重建

只在资源本身可以重建时使用（队列、参数、缓存）。**DynamoDB 有真实会话数据时不用这条。**

```bash
# 在 03-compute 里先 plan 出将被销毁的地址，人工核对后
terraform destroy -target=aws_dynamodb_table.sessions -target=aws_sqs_queue.jobs
# 再到 02-data 里 apply 建新的
```

### 5.3 验证

| 检查 | 命令 | 期望 |
| --- | --- | --- |
| 两站 `03-compute` 零差异 | `make plan site=photos layer=03-compute` | 无变更 |
| `02-data` 零差异 | `make plan site=photos layer=02-data` | 无变更 |
| 表与队列仍在 | `aws dynamodb describe-table --table-name album-sessions` | `ACTIVE` |
| 函数仍能读写 | `curl -s https://<api>/health` | 200 |

### 5.4 回滚

把资源块搬回 `03-compute`，`state rm` + `import` 反向执行。**E6 之前两份 state 都保留**。

---

## 6. E4 · 权威 DNS 迁 Azure

### 6.1 建区并比对

```bash
az network dns zone create -g album-dns-rg -n chenyouyi.top
# 导出 Route 53 现存记录，逐条比对后再导入
aws route53 list-resource-record-sets --hosted-zone-id <ZONE> > r53.json
```

### 6.2 TTL 分层（先降后切）

| 记录类型 | 目标 TTL | 理由 |
| --- | --- | --- |
| 静态资源 / CDN 别名 | 3600 | 变更频率极低 |
| API / 应用子域 | 60 | 需要快速切换 |
| 切换演练用的记录 | 60 | — |

**先把 Route 53 上的 TTL 全部降到 60，等满 1 小时（旧 TTL 过期）再改 NS**，否则切换要等最长 3600 秒才生效。

### 6.3 切 NS

```bash
# 在域名注册商处把 NS 改成 Azure DNS 的四条
az network dns zone show -g album-dns-rg -n chenyouyi.top --query nameServers -o tsv
```

### 6.4 验证

```bash
dig +short NS chenyouyi.top @8.8.8.8        # 应为 Azure 的四条
dig +short photos.chenyouyi.top             # 应解析到现有 CloudFront
curl -sI https://photos.chenyouyi.top | head -1
```

### 6.5 回滚

NS 改回 Route 53 并保留原 zone 至少 30 天（`aws route53 get-hosted-zone` 仍可查）。

---

## 7. E5 · 平台站点落地

```bash
cd sites/platform
for l in 00-bootstrap 01-storage 02-data 03-compute 04-edge; do
  (cd $l && terraform init -input=false -backend-config=backend.hcl \
        && terraform apply -input=false -var-file=prod.tfvars)
done
```

逐层出口判据见 `01` §11.2。全部通过后：

```bash
curl -s https://<platform-domain>/health      # {"status":"UP"}
curl -s -o /dev/null -w '%{http_code}\n' https://<platform-domain>/api/v1/auth/me   # 401
```

平台自身不参与 E1–E4 的任何步骤——它在 `sites/platform` 里独立成长。

---

## 8. E6 · 收尾与冻结

| 动作 | 命令 | 说明 |
| --- | --- | --- |
| 冻结旧 S3 state | `aws s3api put-bucket-versioning --bucket chenyouyi-tfstate --versioning-configuration Status=Suspended` | 不删，只读归档 |
| 删除旧目录残留 | `git rm -r` 已无引用的目录 | 确认 `make plan-all` 全绿后再删 |
| 更新 `README.md` | 按新层序、新 state 重写 §1/§2/§4c | 旧版本进 git 历史 |
| 打 tag | `git tag v6-layout && git push --tags` | 回滚基线 |
| 归档本规格 | `spec/V2` 保持不动 | 后续变更写 `V3` |

---

## 9. 全局验证清单 / Global verification

E6 结束时全部满足，才算执行完成。

| # | 检查 | 命令 / 方式 | 期望 |
| --- | --- | --- | --- |
| V1 | 16 个 scope 全部零差异 | `make plan-all` | 全部「无变更」 |
| V2 | 16 份 state 都在 Blob | `az storage blob list ... --query "length(@)"` | `16` |
| V3 | 层号连续 | `make check` A1 | 通过 |
| V4 | 站点结构同构 | `diff <(ls sites/photos) <(ls sites/management)` | 完全一致 |
| V5 | 站点可访问 | `curl -I https://photos.chenyouyi.top` | 200 |
| V6 | 管理站可开关 | 按仓库 §4b runbook 开一次、关一次 | 两次成功，`gates` 页 `CONSISTENT` |
| V7 | 平台可用 | `/health`、`/api/v1/auth/me` | `UP` / 401 |
| V8 | 权威解析 | `dig NS chenyouyi.top @8.8.8.8` | Azure 四条 |
| V9 | CI 矩阵按层号 | 触发一次 `mode=plan` | job 顺序 `00→01→02→03→04` |
| V10 | 旧 S3 state 只读 | `aws s3 ls s3://chenyouyi-tfstate` | 仍有对象，无写入 |

---

## 10. 风险与应对 / Risks

| # | 风险 | 触发信号 | 应对 |
| --- | --- | --- | --- |
| RK-1 | `01-storage` 的 plan 不为零（桶属性被手改过） | `terraform plan` 出现 `~` 行 | **先改代码对齐线上**，不要 apply；桶里有照片，任何 `-/+` 都要停下来看 |
| RK-2 | `state mv` / `import` 后出现重复资源 | plan 里同时出现 `create` 与既有 ID | 立即 `state rm` 掉多余地址，重新 import；不要 apply |
| RK-3 | Azure 联邦凭据拿不到令牌 | CI 报 `AADSTS70021` | 检查 federated credential 的 `subject` 是否与实际触发分支/环境完全一致 |
| RK-4 | DNS 切换后部分解析器仍指旧 NS | `dig @1.1.1.1` 与 `@8.8.8.8` 不一致 | 属正常，等 TTL；24 小时内不要回滚 |
| RK-5 | 并发 apply 写同一 state | CI 报 lease 冲突 | 检查矩阵 `max-parallel: 1`；站点之间才允许并行 |
| RK-6 | 平台站点首次 apply 卡在数据库初始化 | `02-data` 成功但 `03-compute` 健康检查失败 | 先手工跑一次 Flyway，再 apply `03-compute` |

---

## 11. 谁做什么 / Ownership

| 批次 | 执行 | 复核 |
| --- | --- | --- |
| E0 | Infra Owner | 第二人核对 IAM 范围（只 `Storage Blob Data Contributor`） |
| E1 | 任意开发者（纯代码） | Code review + `make check` |
| E2 | Infra Owner | 每层 `plan` 为零后由第二人确认再下一层 |
| E3 | Infra Owner | 数据类资源迁移前后各做一次快照对比 |
| E4 | Infra Owner + 域名持有人 | 切换窗口内双人在线 |
| E5 | 平台开发 | `01` §11.2 的出口判据逐条签 |
| E6 | Infra Owner | V1–V10 全绿后打 tag |

---

## 12. 与本规格其余文档的关系 / Relation to the rest

| 文档 | 在本计划中的角色 |
| --- | --- |
| `00-summary` | 目标态是什么（本计划要到达的地方） |
| `README` §3 | 六条差异的清单（本计划逐条消化） |
| `01` §11 | 目标态层结构与 state 引导（E1/E2 的依据） |
| `02` R-INV-13 | 平台读 `site.yaml`，校验不过就拒绝加载——E1 之后平台才能正确识别层 |
| `06` | 平台自身的路线图（与仓库改造并行，不互相阻塞） |
| `07` ADR-002/003/041/042 | 为什么这么改（要争论时看这里） |
