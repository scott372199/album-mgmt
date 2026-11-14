package top.chenyouyi.album.mgmt.provider.aws;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import software.amazon.awssdk.services.cloudfront.model.DistributionSummary;
import software.amazon.awssdk.services.cloudfront.model.ListDistributionsRequest;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricDataRequest;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricDataResponse;
import software.amazon.awssdk.services.cloudwatch.model.Metric;
import software.amazon.awssdk.services.cloudwatch.model.MetricDataQuery;
import software.amazon.awssdk.services.cloudwatch.model.MetricDataResult;
import software.amazon.awssdk.services.cloudwatch.model.MetricStat;
import software.amazon.awssdk.services.cloudwatchlogs.model.FilterLogEventsRequest;
import software.amazon.awssdk.services.cloudwatchlogs.model.FilteredLogEvent;
import software.amazon.awssdk.services.costexplorer.model.DateInterval;
import software.amazon.awssdk.services.costexplorer.model.GetCostAndUsageRequest;
import software.amazon.awssdk.services.costexplorer.model.Granularity;
import software.amazon.awssdk.services.costexplorer.model.ResultByTime;
import software.amazon.awssdk.services.ecr.model.DescribeRepositoriesRequest;
import software.amazon.awssdk.services.ecr.model.Repository;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

/**
 * 真实 AWS 只读网关。每个方法只做 list / describe / get，绝不写云资源；
 * 返回 provider-neutral Map，剥离完整 ARN、账号 ID 与内部对象 key。
 * 日志域只取结构化日志的契约白名单字段，绝不把原始日志行带进 HTTP 响应。
 *
 * Real AWS read-only gateway. Every method performs list / describe / get only,
 * never a write. It returns provider-neutral maps with full ARNs, account ids and
 * internal keys stripped. The logs domain keeps only contract whitelisted fields
 * from structured log events; raw log lines never reach an HTTP response.
 */
public final class SdkAwsReadGateway implements AwsReadGateway {

    /** 结构化日志允许出站的字段 / Structured-log fields allowed to leave the service. */
    private static final Set<String> LOG_FIELDS = Set.of(
            "event", "correlationId", "stage", "outcome", "durationMs",
            "provider", "errorClass", "assetId", "attempt", "level", "msg", "ts");

    /** 快照允许出站的字段 / Snapshot fields allowed to leave the service. */
    private static final Set<String> WORKFLOW_FIELDS = Set.of(
            "repository", "branch", "name", "status", "conclusion",
            "commitDigest", "runId", "startedAt", "updatedAt");

    private static final String ACCOUNT_ALIAS = "album-production";
    private static final String SNAPSHOT_SCHEMA = "album.cicd-snapshot.v1";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AwsSdkClients clients;
    private final MgmtAwsProperties props;
    private final Function<GetObjectRequest, String> snapshotReader;

    public SdkAwsReadGateway(AwsSdkClients clients, MgmtAwsProperties props) {
        this(clients, props, request -> clients.s3().getObjectAsBytes(request).asUtf8String());
    }

    /**
     * 可注入快照读取器，让解析与新鲜度逻辑能在零依赖单测里验证。
     * An injectable snapshot reader lets parsing and freshness be unit-tested
     * with no AWS client at all.
     */
    public SdkAwsReadGateway(
            AwsSdkClients clients,
            MgmtAwsProperties props,
            Function<GetObjectRequest, String> snapshotReader) {
        this.clients = clients;
        this.props = props;
        this.snapshotReader = snapshotReader;
    }

    @Override
    public List<Map<String, Object>> resources() {
        List<Map<String, Object>> out = new ArrayList<>();
        String region = props.getRegion();
        if (clients.ecr() != null) {
            var response = clients.ecr().describeRepositories(
                    DescribeRepositoriesRequest.builder().maxResults(props.boundedMaxItems()).build());
            for (Repository repo : response.repositories()) {
                out.add(resource(region, "ecr", repo.repositoryName(), "active",
                        Map.of("imageTagMutability", String.valueOf(repo.imageTagMutability()),
                                "summary", "container image repository")));
            }
        }
        if (clients.ecs() != null) {
            var response = clients.ecs().listClusters();
            for (String arn : response.clusterArns()) {
                out.add(resource(region, "ecs-cluster", shortName(arn), "active",
                        Map.of("summary", "ECS cluster")));
            }
        }
        if (clients.cloudFront() != null) {
            var response = clients.cloudFront().listDistributions(
                    ListDistributionsRequest.builder().build());
            if (response.distributionList() != null) {
                for (DistributionSummary dist : response.distributionList().items()) {
                    out.add(resource("global", "cloudfront", dist.id(), cloudFrontStatus(dist.status()),
                            Map.of("enabled", dist.enabled(), "summary", "CDN distribution")));
                }
            }
        }
        return out;
    }

    @Override
    public List<Map<String, Object>> logs() {
        if (clients.cloudWatchLogs() == null || props.getLogGroups().isEmpty()) {
            throw new AwsReadUnavailableException("CloudWatch Logs client or log groups not configured");
        }
        long startMillis = Instant.now()
                .minus(Math.max(1, props.getLogLookbackMinutes()), ChronoUnit.MINUTES)
                .toEpochMilli();
        List<Map<String, Object>> out = new ArrayList<>();
        int cap = props.boundedMaxItems();
        for (String group : props.getLogGroups()) {
            if (out.size() >= cap) {
                break;
            }
            var response = clients.cloudWatchLogs().filterLogEvents(
                    FilterLogEventsRequest.builder()
                            .logGroupName(group)
                            .startTime(startMillis)
                            .limit(Math.min(100, cap - out.size()))
                            .build());
            for (FilteredLogEvent event : response.events()) {
                if (out.size() >= cap) {
                    break;
                }
                Map<String, Object> structured = structuredLog(event, group);
                if (structured != null) {
                    out.add(structured);
                }
            }
        }
        if (out.isEmpty()) {
            throw new AwsReadUnavailableException("no structured log events in the lookback window");
        }
        return out;
    }

    /** 每个 Lambda 函数查询三个 AWS/Lambda 指标 / Three metrics per Lambda function. */
    static final String[] METRIC_NAMES = {"Invocations", "Errors", "Duration"};

    /**
     * 查询必须带 FunctionName dimension：AWS/Lambda 指标没有无 dimension 的聚合样本，
     * 不带 dimension 会返回空结果，若照旧包装就是伪造的 0。
     *
     * Queries must carry a FunctionName dimension: AWS/Lambda has no
     * dimension-less aggregate, so a query without it yields empty samples that
     * must never be reported as a real zero.
     */
    static List<MetricDataQuery> metricQueries(MgmtAwsProperties props) {
        List<MetricDataQuery> queries = new ArrayList<>();
        int index = 0;
        for (String function : props.boundedLambdaFunctions()) {
            for (String metricName : METRIC_NAMES) {
                queries.add(MetricDataQuery.builder()
                        .id("q" + index++)
                        .returnData(true)
                        .metricStat(MetricStat.builder()
                                .period(300)
                                .stat("Duration".equals(metricName) ? "Average" : "Sum")
                                .metric(Metric.builder()
                                        .namespace(props.getMetricNamespace())
                                        .metricName(metricName)
                                        .dimensions(Dimension.builder()
                                                .name("FunctionName")
                                                .value(function)
                                                .build())
                                        .build())
                                .build())
                        .build());
            }
        }
        return queries;
    }

    /** 无样本返回 null，绝不产出假的 0 值行 / No samples → null, never a fake zero row. */
    static Map<String, Object> metricRow(MetricDataResult result, MgmtAwsProperties props) {
        if (result == null || result.values() == null || result.values().isEmpty()) {
            return null;
        }
        int index = queryIndex(result.id());
        List<String> functions = props.boundedLambdaFunctions();
        if (index < 0 || index / METRIC_NAMES.length >= functions.size()) {
            return null;
        }
        String function = functions.get(index / METRIC_NAMES.length);
        String metricName = METRIC_NAMES[index % METRIC_NAMES.length];
        double value = 0;
        for (Double sample : result.values()) {
            value += sample == null ? 0 : sample;
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("function", function);
        row.put("metric", props.getMetricNamespace() + "." + metricName);
        row.put("stage", "processor");
        row.put("unit", "Duration".equals(metricName) ? "milliseconds" : "count");
        row.put("registered", true);
        row.put("value", Math.round(value * 100.0) / 100.0);
        row.put("samples", result.values().size());
        row.put("valueSource", "cloudwatch:GetMetricData");
        return row;
    }

    private static int queryIndex(String id) {
        if (id == null || !id.startsWith("q")) {
            return -1;
        }
        try {
            return Integer.parseInt(id.substring(1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    @Override
    public List<Map<String, Object>> metrics() {
        if (clients.cloudWatch() == null) {
            throw new AwsReadUnavailableException("CloudWatch client not configured");
        }
        List<MetricDataQuery> queries = metricQueries(props);
        if (queries.isEmpty()) {
            throw new AwsReadUnavailableException("no Lambda functions configured for metrics");
        }
        Instant end = Instant.now().truncatedTo(ChronoUnit.MINUTES);
        Instant start = end.minus(60, ChronoUnit.MINUTES);
        GetMetricDataResponse response = clients.cloudWatch().getMetricData(
                GetMetricDataRequest.builder()
                        .startTime(start)
                        .endTime(end)
                        .metricDataQueries(queries)
                        .build());
        List<Map<String, Object>> out = new ArrayList<>();
        int cap = props.boundedMaxItems();
        for (MetricDataResult result : response.metricDataResults()) {
            if (out.size() >= cap) {
                break;
            }
            Map<String, Object> row = metricRow(result, props);
            if (row != null) {
                out.add(row);
            }
        }
        if (out.isEmpty()) {
            throw new AwsReadUnavailableException("no CloudWatch metric samples in the last hour");
        }
        return out;
    }

    @Override
    public List<Map<String, Object>> billing() {
        if (clients.costExplorer() == null) {
            throw new AwsReadUnavailableException("Cost Explorer client not configured");
        }
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate monthStart = today.withDayOfMonth(1);
        var request = GetCostAndUsageRequest.builder()
                .timePeriod(DateInterval.builder()
                        .start(monthStart.toString())
                        .end(today.toString())
                        .build())
                .granularity(Granularity.MONTHLY)
                .metrics("UnblendedCost")
                .build();
        var response = clients.costExplorer().getCostAndUsage(request);
        List<Map<String, Object>> out = new ArrayList<>();
        if (response.resultsByTime() != null) {
            for (ResultByTime period : response.resultsByTime()) {
                String amount = period.total() == null ? null
                        : period.total().get("UnblendedCost") == null ? null
                        : period.total().get("UnblendedCost").amount();
                if (amount == null) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("period", period.timePeriod() == null ? "unknown" : period.timePeriod().start());
                row.put("scope", "account-month-to-date");
                row.put("amount", amount);
                row.put("currency", response.resultsByTime().isEmpty() ? "USD"
                        : currencyOf(period));
                row.put("valueSource", "ce:GetCostAndUsage");
                out.add(row);
            }
        }
        if (out.isEmpty()) {
            throw new AwsReadUnavailableException("Cost Explorer returned no month-to-date rows");
        }
        return out;
    }

    @Override
    public List<Map<String, Object>> workflows() {
        if (!props.snapshotConfigured() || snapshotReader == null) {
            throw new AwsReadUnavailableException("CI workflow snapshot bucket not configured");
        }
        String body;
        try {
            body = snapshotReader.apply(GetObjectRequest.builder()
                    .bucket(props.getSnapshotBucket())
                    .key(props.getSnapshotKey())
                    .build());
        } catch (RuntimeException e) {
            throw new AwsReadUnavailableException("CI workflow snapshot object is not readable");
        }
        if (body == null || body.isBlank()) {
            throw new AwsReadUnavailableException("CI workflow snapshot object is empty");
        }
        Map<String, Object> snapshot;
        try {
            snapshot = MAPPER.readValue(body, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            throw new AwsReadUnavailableException("CI workflow snapshot is not valid JSON");
        }
        if (!SNAPSHOT_SCHEMA.equals(String.valueOf(snapshot.get("schema")))) {
            throw new AwsReadUnavailableException("CI workflow snapshot schema is unsupported");
        }
        checkFreshness(snapshot.get("generatedAt"));
        Object rawRuns = snapshot.get("runs");
        if (!(rawRuns instanceof List<?> runs) || runs.isEmpty()) {
            throw new AwsReadUnavailableException("CI workflow snapshot contains no runs");
        }
        List<Map<String, Object>> out = new ArrayList<>();
        int cap = props.boundedMaxItems();
        for (Object entry : runs) {
            if (out.size() >= cap) {
                break;
            }
            if (!(entry instanceof Map<?, ?> raw)) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            for (String field : WORKFLOW_FIELDS) {
                Object value = raw.get(field);
                if (value != null && !(value instanceof Map) && !(value instanceof List)) {
                    row.put(field, value);
                }
            }
            if (row.containsKey("repository") && row.containsKey("name")) {
                out.add(row);
            }
        }
        if (out.isEmpty()) {
            throw new AwsReadUnavailableException("CI workflow snapshot has no usable runs");
        }
        return out;
    }

    @Override
    public List<Map<String, Object>> securityControls() {
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(control("read-only API boundary", "no write endpoints registered in DomainReadController"));
        out.add(control("least-privilege task role", "cloud adapters call list/describe/get only"));
        out.add(control("no long-lived credentials", "ECS task role supplies short-lived credentials"));
        out.add(control("response redaction", "ForbiddenFields applied before serialization"));
        out.add(control("no raw log lines", "logs domain keeps structured contract fields only"));
        return out;
    }

    private void checkFreshness(Object generatedAt) {
        if (!(generatedAt instanceof String text)) {
            throw new AwsReadUnavailableException("CI workflow snapshot has no generatedAt");
        }
        try {
            Instant generated = Instant.parse(text);
            long maxAge = Math.max(1, props.getSnapshotMaxAgeMinutes());
            if (generated.isBefore(Instant.now().minus(maxAge, ChronoUnit.MINUTES))) {
                throw new AwsReadUnavailableException("CI workflow snapshot is stale");
            }
        } catch (java.time.format.DateTimeParseException e) {
            throw new AwsReadUnavailableException("CI workflow snapshot generatedAt is invalid");
        }
    }

    static Map<String, Object> structuredLog(FilteredLogEvent event, String group) {
        String message = event.message();
        if (message == null || !message.trim().startsWith("{")) {
            return null; // 原始文本日志可能含 PII，一律不带出站 / never expose raw text lines
        }
        Map<String, Object> parsed;
        try {
            parsed = MAPPER.readValue(message, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return null;
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("logGroup", group);
        row.put("logStream", event.logStreamName());
        row.put("timestamp", Instant.ofEpochMilli(event.timestamp()).toString());
        for (String field : LOG_FIELDS) {
            Object value = parsed.get(field);
            if (value != null && !(value instanceof Map) && !(value instanceof List)) {
                row.put(field, value);
            }
        }
        return row.size() > 3 ? row : null;
    }

    private Map<String, Object> resource(
            String region, String type, String id, String status, Map<String, Object> extensions) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("provider", "aws");
        row.put("account", ACCOUNT_ALIAS);
        row.put("region", region);
        row.put("type", type);
        row.put("id", id == null ? "unknown" : id);
        row.put("status", status);
        row.put("tags", Map.of("project", "album", "managed", "true"));
        row.put("extensions", new LinkedHashMap<>(extensions));
        return row;
    }

    private Map<String, Object> control(String name, String evidence) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("control", name);
        row.put("severity", "info");
        row.put("status", "enforced");
        row.put("evidence", evidence);
        row.put("remediation", "n/a");
        return row;
    }

    static String cloudFrontStatus(String status) {
        if (status == null) {
            return "unknown";
        }
        return switch (status) {
            case "Deployed" -> "active";
            case "InProgress" -> "degraded";
            default -> "unknown";
        };
    }

    private String shortName(String arn) {
        if (arn == null) {
            return "unknown";
        }
        int slash = arn.lastIndexOf('/');
        int colon = arn.lastIndexOf(':');
        return arn.substring(Math.max(slash, colon) + 1);
    }

    private String currencyOf(ResultByTime period) {
        if (period.total() == null || period.total().get("UnblendedCost") == null
                || period.total().get("UnblendedCost").unit() == null) {
            return "USD";
        }
        return period.total().get("UnblendedCost").unit();
    }
}
