package top.chenyouyi.album.mgmt.provider.aws;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * cloud profile 的只读数据源参数。全部来自环境变量或默认值，不含任何凭据；
 * AWS 凭据由 ECS task role 提供，容器里不存在长期密钥。
 *
 * Read-only data-source settings for the cloud profile. Every value comes from an
 * environment variable or a default; no credential is stored here. AWS credentials
 * come exclusively from the ECS task role, so the container holds no long-lived key.
 */
@ConfigurationProperties(prefix = "mgmt.aws")
public class MgmtAwsProperties {

    private String region = "ap-southeast-1";
    private String globalRegion = "us-east-1";
    private String snapshotBucket = "";
    private String snapshotKey = "cicd/workflows.json";
    private int snapshotMaxAgeMinutes = 180;
    private List<String> logGroups = List.of(
            "/aws/lambda/album-auth",
            "/aws/lambda/album-processor",
            "/aws/ecs/album-mgmt");
    private int logLookbackMinutes = 1440;
    private String metricNamespace = "AWS/Lambda";
    // CloudWatch 的 AWS/Lambda 指标必须带 FunctionName dimension 才有样本；
    // 不带 dimension 的查询会返回空值，被误当成真实的 0。
    // AWS/Lambda metrics only have samples with a FunctionName dimension;
    // a dimension-less query returns nothing and must not be reported as a real zero.
    private List<String> lambdaFunctions = List.of("album-auth", "album-processor");
    private int maxItems = 40;
    private int timeoutMillis = 4000;

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getGlobalRegion() {
        return globalRegion;
    }

    public void setGlobalRegion(String globalRegion) {
        this.globalRegion = globalRegion;
    }

    public String getSnapshotBucket() {
        return snapshotBucket;
    }

    public void setSnapshotBucket(String snapshotBucket) {
        this.snapshotBucket = snapshotBucket;
    }

    public String getSnapshotKey() {
        return snapshotKey;
    }

    public void setSnapshotKey(String snapshotKey) {
        this.snapshotKey = snapshotKey;
    }

    public int getSnapshotMaxAgeMinutes() {
        return snapshotMaxAgeMinutes;
    }

    public void setSnapshotMaxAgeMinutes(int snapshotMaxAgeMinutes) {
        this.snapshotMaxAgeMinutes = snapshotMaxAgeMinutes;
    }

    public List<String> getLogGroups() {
        return logGroups;
    }

    public void setLogGroups(List<String> logGroups) {
        this.logGroups = logGroups == null ? List.of() : List.copyOf(logGroups);
    }

    public int getLogLookbackMinutes() {
        return logLookbackMinutes;
    }

    public void setLogLookbackMinutes(int logLookbackMinutes) {
        this.logLookbackMinutes = logLookbackMinutes;
    }

    public String getMetricNamespace() {
        return metricNamespace;
    }

    public void setMetricNamespace(String metricNamespace) {
        this.metricNamespace = metricNamespace;
    }

    public List<String> getLambdaFunctions() {
        return lambdaFunctions;
    }

    public void setLambdaFunctions(List<String> lambdaFunctions) {
        if (lambdaFunctions == null) {
            this.lambdaFunctions = List.of();
            return;
        }
        // List.copyOf 不接受 null 元素；空值替换成空串，由 boundedLambdaFunctions 统一剔除。
        // List.copyOf rejects nulls; map them to blanks and let boundedLambdaFunctions drop them.
        List<String> safe = new ArrayList<>(lambdaFunctions.size());
        for (String name : lambdaFunctions) {
            safe.add(name == null ? "" : name);
        }
        this.lambdaFunctions = List.copyOf(safe);
    }

    /**
     * 归一化函数名：去空白、去重、最多 8 个，防止异常配置把 CloudWatch 查询放大。
     * Normalise function names: trim, deduplicate and cap at 8 to bound the query.
     */
    public List<String> boundedLambdaFunctions() {
        Set<String> clean = new LinkedHashSet<>();
        if (lambdaFunctions != null) {
            for (String name : lambdaFunctions) {
                if (name == null || name.isBlank()) {
                    continue;
                }
                clean.add(name.trim());
                if (clean.size() >= 8) {
                    break;
                }
            }
        }
        return new ArrayList<>(clean);
    }

    public int getMaxItems() {
        return maxItems;
    }

    public void setMaxItems(int maxItems) {
        this.maxItems = maxItems;
    }

    public int getTimeoutMillis() {
        return timeoutMillis;
    }

    public void setTimeoutMillis(int timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }

    /** 快照配置是否完整 / Whether the CI snapshot source is configured. */
    public boolean snapshotConfigured() {
        return snapshotBucket != null && !snapshotBucket.isBlank()
                && snapshotKey != null && !snapshotKey.isBlank();
    }

    /** 上限归一到 [1, 200]，避免异常配置放大响应 / Clamp the cap to protect response size. */
    public int boundedMaxItems() {
        return Math.max(1, Math.min(200, maxItems));
    }

    /** 超时归一到 [500, 15000] 毫秒 / Clamp the per-call timeout in milliseconds. */
    public int boundedTimeoutMillis() {
        return Math.max(500, Math.min(15000, timeoutMillis));
    }
}
