package top.chenyouyi.album.mgmt.provider.fixture;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import top.chenyouyi.album.mgmt.common.ContractMetrics;

/**
 * 本地只读 fixture 数据（无数据库、无真实云、无敏感字段）。
 * 严格使用契约 resourceDto 字段；不返回完整 ARN、SSM 值、内部对象 key 或 vendor DTO。
 *
 * Local read-only fixture data (no database, no real cloud, no sensitive fields).
 * Uses the contract resourceDto shape; never returns full ARNs, SSM values, internal
 * object keys or vendor DTOs.
 */
public final class FixtureData {

    private FixtureData() {
    }

    /**
     * 资源目录 / Resource inventory (contract resourceDto).
     *
     * 只读界面不允许断言它没验证过的生命周期状态。原来 ECS Express 与 cx 分发
     * 报 `planned`（「已规划、尚未上线」），但真实资源既可能 ACTIVE / Deployed，
     * 也可能已经销毁 —— 那是与事实相反的结论（V5 release blocker #6）。
     * fixture 没有真实清单权限，一律报 `unknown`；生产由真实只读清单 adapter
     * 给出 active / degraded / unknown。ECR 仓库是唯一可静态确证存在的资源。
     *
     * A read-only surface must not assert a lifecycle state it never verified.
     * ECS Express and the cx distribution used to report `planned` ("planned,
     * not yet live") while the real resources may be ACTIVE / Deployed or already
     * destroyed — the opposite of reality (V5 blocker #6). Fixtures hold no
     * inventory permission, so they report `unknown`; production resolves
     * active / degraded / unknown through the real read-only adapter. The ECR
     * repository is the only resource whose existence is statically verifiable.
     */
    public static List<Map<String, Object>> resources() {
        return List.of(
                resource("aws", "demo-account", "ap-southeast-1", "ecs-express",
                        "mgmt-ecs-express", "unknown"),
                resource("aws", "demo-account", "global", "cloudfront",
                        "mgmt-cx-distribution", "unknown"),
                resource("aws", "demo-account", "ap-southeast-1", "ecr",
                        "album-mgmt", "active"));
    }

    private static Map<String, Object> resource(
            String provider, String account, String region, String type, String id, String status) {
        // id 是安全摘要标识，不是完整 ARN / id is a safe identifier, never a full ARN
        return obj(
                "provider", provider,
                "account", account,
                "region", region,
                "type", type,
                "id", id,
                "status", status,
                "tags", obj("project", "album", "environment", "management", "managed", "true"),
                "extensions", obj("summary", "read-only inventory fixture; no vendor DTO exposed"));
    }

    /** CI/CD 工作流 / CI/CD workflows. */
    public static List<Map<String, Object>> workflows() {
        return List.of(
                obj("repository", "album", "branch", "main", "name", "deploy-web",
                        "status", "completed", "conclusion", "success", "commitDigest", "fixture-0001"),
                obj("repository", "album-mgmt", "branch", "main", "name", "container",
                        "status", "completed", "conclusion", "success", "commitDigest", "fixture-0002"));
    }

    /**
     * 指标目录：只列契约中已注册的指标定义，不伪造实时数值。
     * Metrics catalog: lists registered contract metric definitions only, never fakes live values.
     */
    public static List<Map<String, Object>> metricCatalog() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String metric : ContractMetrics.ALL) {
            out.add(obj(
                    "metric", metric,
                    "stage", stageFor(metric),
                    "unit", unitFor(metric),
                    "registered", true,
                    "valueSource", "/actuator/prometheus"));
        }
        return out;
    }

    /** 安全控制姿态（契约 security common model）/ Security control posture. */
    public static List<Map<String, Object>> securityControls() {
        return List.of(
                obj("control", "read-only API boundary", "severity", "info",
                        "status", "enforced", "evidence", "no write endpoints registered",
                        "remediation", "n/a"),
                obj("control", "independent mgmt JWT secret", "severity", "info",
                        "status", "enforced", "evidence", "MGMT_JWT_SECRET only",
                        "remediation", "n/a"),
                obj("control", "no long-lived cloud credentials in container", "severity", "info",
                        "status", "enforced", "evidence", "iamBoundaries.album-mgmt-container",
                        "remediation", "n/a"));
    }

    private static String stageFor(String metric) {
        if (metric.startsWith("upload")) return "upload";
        if (metric.startsWith("processor")) return "processor";
        if (metric.startsWith("recognition")) return "recognition";
        if (metric.startsWith("dlq")) return "publish";
        if (metric.startsWith("manifest")) return "publish";
        if (metric.startsWith("cicd")) return "cicd";
        if (metric.startsWith("management")) return "management";
        if (metric.startsWith("cost")) return "infra";
        return "management";
    }

    private static String unitFor(String metric) {
        if (metric.endsWith("_ms")) return "milliseconds";
        if (metric.endsWith("_rate") || metric.endsWith("_depth") || metric.endsWith("_count")
                || metric.endsWith("_retry") || metric.endsWith("_conflict")) return "count";
        return "gauge";
    }

    /** 便捷构造保序 Map / Helper building an order-preserving Map. */
    static Map<String, Object> obj(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return map;
    }
}
