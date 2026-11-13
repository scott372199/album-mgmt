# album-mgmt · 相册管理平台 / Album management platform

这是 V5 的管理平台原型，使用 Java/Spring Boot 提供 read-only API，
使用 React/Vite 提供管理界面。

*This is the V5 management-platform prototype. It provides read-only APIs with
Java/Spring Boot and a management UI with React/Vite.*

## 当前边界 / Current boundary

- 站点：`https://cx.chenyouyi.top`
- 入口：独立 CloudFront distribution → ECS Express Mode
- TLS：CloudFront edge termination；CloudFront 到 ECS Express custom origin 保持 HTTPS
- 数据：原型阶段使用 mock/fixture，不引入数据库
- 观测：OpenTelemetry → Grafana/Loki/Prometheus/Tempo
- 权限：只读；不直接执行 Terraform apply，不修改 IAM，不删除云资源
- API：`GET /api/health` 与 `GET /api/management/snapshot` 当前公开且只返回安全摘要
- 观测：Actuator health/info/metrics 与 Prometheus endpoint 已开启；OTel Collector/Grafana 仍是后续部署层
- tracing：Micrometer → OpenTelemetry bridge 已接入；默认 sampling 为 `0`，配置 `OTEL_TRACES_SAMPLING` 与 `OTEL_EXPORTER_OTLP_ENDPOINT` 后才发送 traces

## ECS Express 发布前流程

推荐使用 `.github/workflows/container.yml` 在 GitHub-hosted `linux/amd64` runner 构建并推送 immutable image；不依赖本机 Docker daemon，也不使用 `latest`。workflow 需要配置 OIDC role variable `AWS_IMAGE_PUBLISH_ROLE_ARN`，并将 digest artifact 中的 URI 设置为 Terraform 的 `management_image_uri`。

本地 Docker daemon 可用时，也可以手工执行：

```bash
export AWS_REGION=ap-east-1
export AWS_ACCOUNT_ID="$(aws sts get-caller-identity --query Account --output text)"
export IMAGE_REPOSITORY="${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com/chenyouyi-album-mgmt"
export IMAGE_TAG="$(git rev-parse --short=12 HEAD)-$(date -u +%Y%m%d%H%M%S)"

aws ecr get-login-password --region "$AWS_REGION" |
  docker login --username AWS --password-stdin "${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com"
docker build --platform linux/amd64 -t "${IMAGE_REPOSITORY}:${IMAGE_TAG}" .
docker push "${IMAGE_REPOSITORY}:${IMAGE_TAG}"
docker inspect --format='{{index .RepoDigests 0}}' "${IMAGE_REPOSITORY}:${IMAGE_TAG}"
```

将返回的 digest 设置为 `management_image_uri` 后，只执行 `30-compute` 和 `40-management-edge` 的 read-only Terraform plan；不要在验证前执行 apply 或 DNS 切换。

*The prototype is read-only, uses fixtures instead of a database, and cannot
apply Terraform, modify IAM or delete cloud resources. The snapshot API exposes
provider-neutral summaries only.*

## 目录约定 / Layout convention

```text
app/
  src/main/java/top/chenyouyi/album/mgmt/
    identity/
    cicd/
    inventory/
    observability/
    security/
    billing/
    audit/
    provider/
web/
  src/
contracts/
scripts/
```

模块之间只能通过 application service 或 provider port 通信；AWS、GitHub、
Loki、Prometheus 和 Tempo 的 SDK/API 适配器不得泄漏到公共 DTO。

*Modules communicate through application services or provider ports. AWS,
GitHub, Loki, Prometheus and Tempo adapters must not leak into public DTOs.*

## 本地检查 / Local checks

```bash
node scripts/check.mjs
./gradlew test
./gradlew :app:bootJar
./gradlew :app:bootRun
npm run web:install
npm run web:build
```

`check.mjs` 会检查敏感文件、绝对路径、相册站点与管理站域名混用，
以及原型阶段禁止出现的生产写操作标记。
当前管理站保持 public read-only，不要求登录。未来开放 write API 时，
认证应复用相册上传的手机号 allowlist + scrypt shared key + token 流程；
不要为管理站引入第二套密码体系。启用 `provider` profile 时还需要
`AWS_REGION`、`GITHUB_REPOSITORY` 和
`GITHUB_TOKEN`；默认 profile 只使用 fixture，不访问外部 provider。

*The check script scans for secrets, absolute paths, mixed album/management
origins and production write-operation markers forbidden in the prototype.*

