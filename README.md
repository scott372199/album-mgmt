# album-mgmt · 相册管理平台 / Album management platform

V5 重建后的管理平台（Java 21 + Spring Boot 只读 API + React/Vite 管理界面）。
本地默认跑 fixture 数据，零云依赖即可启动；接真实云只换 provider 实现，不动公共 DTO。

*The V5-rebuilt management platform: read-only API on Java 21 / Spring Boot plus a
React/Vite console. Locally it runs on fixture data with zero cloud dependencies;
switching to real clouds only swaps provider implementations, never the public DTOs.*

## 契约与边界 / Contract and boundary

- 契约单一来源：`个人相册/spec/v5/contracts/v5-contracts.json`（Envelope、correlationId、forbiddenFields、identity、providerPorts）
- 站点：`https://cx.chenyouyi.top`（独立 CloudFront → ECS Express，与相册站互不依赖）
- 只读硬边界：无任何写端点；不执行 Terraform apply、不改 IAM、不删云资源
- 降级语义：adapter 不可用时返回 `source=unavailable` + 原因，**绝不伪造数据**
- 隐私：主体是哈希派生（pepper + principal 的 SHA-256），绝不落明文手机号；
  审计与所有响应在写出前经 `ForbiddenFields` 递归过滤（键名 + 敏感值模式双保险）
- JWT：独立 `MGMT_JWT_SECRET`，与相册上传侧完全独立；未设置时进程内随机生成

*Single source of truth for contracts: `spec/v5/contracts/v5-contracts.json`.
Site: cx.chenyouyi.top (independent CloudFront → ECS Express). Hard read-only boundary:
no write endpoints, no Terraform apply, no IAM changes. Unavailable adapters return
source=unavailable with a reason and never fabricate data. Subjects are hash-derived;
audit and every response pass recursive ForbiddenFields redaction. JWT uses the
independent MGMT_JWT_SECRET (random in-process when unset).*

## 认证与 RBAC / Auth and RBAC

- 登录：`POST /api/v1/auth/login`，主体（白名单）+ scrypt 共享口令；签发 HS256 access（1h）/ refresh（7d）
- 演示账户：`admin|admin,auditor|auditor,viewer|viewer`，共享口令 `album-mgmt-demo`（`MGMT_DEMO_ACCOUNTS` / `MGMT_DEMO_SHARED_KEY` 可覆盖）
- 逐域权限：admin 七域全读；auditor 仅 `audit` + `security`；viewer 仅 `resources` + `cicd`
- 未带令牌 401 `missing_bearer_token`；令牌无效 401 `invalid_access_token`；越权 403 `domain_permission_denied`
- 每个六域请求都落审计事件（who 哈希 / domain / action / correlationId / outcome / durationMs）

*Login posts principal (allowlist) + scrypt shared key and issues HS256 access/refresh
tokens. Demo accounts and the shared key are configurable. Permissions are per-domain
(admin: all seven; auditor: audit+security; viewer: resources+cicd). Missing/invalid
tokens are 401, cross-domain reads are 403, and every domain read is audited.*

## API 一览 / API surface

| 端点 / Endpoint | 域 / Domain | 本地 fixture / Local fixture |
| --- | --- | --- |
| `GET /api/v1/cicd/workflows` | CI/CD | fixture |
| `GET /api/v1/resources` | Resources | fixture |
| `GET /api/v1/logs` | Logs | unavailable（Loki adapter 未接） |
| `GET /api/v1/metrics` | Metrics | fixture |
| `GET /api/v1/security` | Security | fixture |
| `GET /api/v1/billing` | Billing | unavailable（Cost Explorer adapter 未接） |
| `GET /api/v1/audit` | Audit | 真实请求事件 + 种子 / real events + seeds |
| `GET /api/v1/health` | 公开 / public | ok |
| `POST /api/v1/auth/login\|refresh\|logout` | 公开 / public | 演示账户 |

统一信封：`{items, source, asOf, correlationId, unavailable}`；客户端传合法
`X-Correlation-Id`（`corr_[0-9a-f]{16}`）则透传，否则服务端生成并在响应头回显。

*Common envelope {items, source, asOf, correlationId, unavailable}. A format-valid
X-Correlation-Id is propagated; otherwise the server generates and echoes one.*

## 目录约定 / Layout convention

```text
app/src/main/java/top/chenyouyi/album/mgmt/
  common/      Envelope / ForbiddenFields / CorrelationId / Crypto / ScryptUtil / 指标
  identity/    AuthController / JwtService / RolePermissions（登录与逐域 RBAC）
  security/    CorrelationAuditFilter（审计+延迟指标）/ DomainSecurityFilter（401/403）
  provider/
    port/      10 个 provider 中立接口 / 10 provider-neutral ports
    fixture/   本地演示数据（默认 profile）
    aws/       AWS 只读 adapter（cloud profile）
    github/    GitHub Actions 只读 adapter
  web/         七域只读 Controller + HealthController + 出站过滤 Advice
app/src/test/  28 项测试（JWT、RBAC、审计、过滤、端到端安全矩阵）
web/           React 控制台（登录 + 七域信封渲染 + 降级展示）+ nginx.conf
scripts/       check.mjs 离线自检
```

模块之间只通过 provider port 通信；AWS、GitHub、Loki、Prometheus、Tempo 的 SDK
适配器不得泄漏到公共 DTO。

*Modules communicate only through provider ports; vendor adapters never leak into
public DTOs.*

## 本地运行 / Run locally

```bash
# 后端（端口用命令行参数显式指定；见下方端口注意事项）
MGMT_JWT_SECRET=local-dev-secret ./gradlew :app:bootRun --args='--server.port=8095'
# 前端
npm run web:install && npm --prefix web run dev   # /api 已代理到 127.0.0.1:8080
# 或一键容器演示
docker compose up --build                        # 打开 http://127.0.0.1:8090
```

**端口注意事项**：本机若存在 `SERVER__PORT` 环境变量，Spring 宽松绑定会把它映射成
`server.port` 并覆盖 application.yml（环境变量优先）。本地起服务一律用
`--args='--server.port=...'` 显式指定。

*Note: a stray SERVER__PORT env var maps to server.port via relaxed binding and wins
over application.yml; always pass --server.port explicitly for local runs.*

## 本地检查 / Local checks

```bash
node scripts/check.mjs        # 敏感样例 / 绝对路径 / 域名混用 / 生产写标记
./gradlew test                # 28 项测试
./gradlew :app:bootJar
npm run web:build
```

`check.mjs` 禁止仓库出现完整 AKIA 样例、私钥标记、GitHub token、绝对路径、
两个站点域名混用，以及 `terraform apply` / `iam:*` / `DeleteResource` 等生产写标记。

*check.mjs scans for sample keys, private-key markers, GitHub tokens, absolute paths,
mixed origins and production write markers forbidden in the prototype.*

## 镜像发布 / Image publishing

推荐用 `.github/workflows/container.yml` 在 GitHub-hosted `linux/amd64` runner 构建并
推送 immutable image（OIDC role `AWS_IMAGE_PUBLISH_ROLE_ARN`，仅 `main` 分支，trust
policy 限定 `repo:scott372199/album-mgmt:*`）；返回的 digest 作为 Terraform 的
`management_image_uri`。本地 Docker 可用时也可手工 push，命令同 v4 时代。

*Prefer the GitHub workflow for immutable linux/amd64 images (OIDC role, main branch
only, digest fed to Terraform as management_image_uri). Manual push stays possible.*
