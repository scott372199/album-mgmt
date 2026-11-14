package top.chenyouyi.album.mgmt.provider.aws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.MetricDataQuery;
import software.amazon.awssdk.services.cloudwatch.model.MetricDataResult;
import software.amazon.awssdk.services.cloudwatchlogs.model.FilteredLogEvent;

import top.chenyouyi.album.mgmt.common.ProviderResult;

/**
 * cloud 只读 adapter 的零依赖单测：不构造任何 AWS client、不连真实云。
 * 覆盖真实来源、诚实降级、脱敏、条数上限与 #6 的状态映射。
 *
 * Dependency-free unit tests for the cloud read adapters: no AWS client is built
 * and no cloud call is made. Covers real sources, honest degradation, redaction,
 * item caps and the blocker #6 status mapping.
 */
class CloudReadAdaptersTest {

    private static MgmtAwsProperties props() {
        MgmtAwsProperties props = new MgmtAwsProperties();
        props.setMaxItems(3);
        props.setSnapshotBucket("mgmt-data");
        props.setSnapshotKey("cicd/workflows.json");
        return props;
    }

    private static Map<String, Object> row(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return map;
    }

    /** 可控替身：每个域返回固定行，或按 need 抛出指定异常 / Controllable test double. */
    static class Stub implements AwsReadGateway {

        RuntimeException failure;

        @Override
        public List<Map<String, Object>> resources() {
            return checked(List.of(row(
                    "provider", "aws", "account", "album-production", "region", "ap-southeast-1",
                    "type", "ecr", "id", "album-processor", "status", "active",
                    "tags", Map.of("project", "album"), "extensions", Map.of("summary", "image repo"))));
        }

        @Override
        public List<Map<String, Object>> logs() {
            return checked(List.of(row(
                    "logGroup", "/aws/lambda/album-processor", "event", "process.done",
                    "correlationId", "corr_bb6f8616101611d0", "stage", "processor",
                    "outcome", "success", "durationMs", 3037)));
        }

        @Override
        public List<Map<String, Object>> metrics() {
            return checked(List.of(row(
                    "metric", "AWS/Lambda.Invocations", "stage", "processor",
                    "unit", "count", "value", 12.0, "valueSource", "cloudwatch:GetMetricData")));
        }

        @Override
        public List<Map<String, Object>> billing() {
            return checked(List.of(row(
                    "period", "2026-11-01", "scope", "account-month-to-date",
                    "amount", "1.23", "currency", "USD", "valueSource", "ce:GetCostAndUsage")));
        }

        @Override
        public List<Map<String, Object>> workflows() {
            return checked(List.of(row(
                    "repository", "scott372199/album", "branch", "main", "name", "build-image",
                    "status", "completed", "conclusion", "success", "commitDigest", "bd9d122")));
        }

        @Override
        public List<Map<String, Object>> securityControls() {
            return checked(List.of(row(
                    "control", "read-only API boundary", "severity", "info",
                    "status", "enforced", "evidence", "no write endpoints", "remediation", "n/a")));
        }

        private <T> T checked(T value) {
            if (failure != null) {
                throw failure;
            }
            return value;
        }
    }

    @Test
    void everyDataDomainReportsARealAwsSource() {
        Stub stub = new Stub();
        MgmtAwsProperties props = props();
        List<ProviderResult> results = List.of(
                new AwsCloudReadAdapter(stub, props).resources(),
                new AwsObservabilityReadAdapter(stub, props).events(),
                new AwsObservabilityReadAdapter(stub, props).series(),
                new AwsBillingReadAdapter(stub, props).records(),
                new WorkflowSnapshotReadAdapter(stub, props).workflows(),
                new AwsSecurityReadAdapter(stub, props).controls());
        for (ProviderResult result : results) {
            assertEquals("aws", result.source());
            assertNull(result.unavailable());
            assertFalse(result.items().isEmpty(), "a real source must not be empty");
        }
    }

    @Test
    void tracesAndAlertsAreHonestInsteadOfFaked() {
        AwsObservabilityReadAdapter adapter = new AwsObservabilityReadAdapter(new Stub(), props());
        for (ProviderResult result : List.of(adapter.traces(), adapter.alerts())) {
            assertEquals("unavailable", result.source());
            assertTrue(result.items().isEmpty());
            assertNotNull(result.unavailable());
        }
    }

    @Test
    void upstreamExceptionTextNeverLeaksIntoTheResponse() {
        Stub stub = new Stub();
        stub.failure = new RuntimeException("arn:aws:iam::123456789012:role/internal-secret");
        ProviderResult result = new AwsCloudReadAdapter(stub, props()).resources();
        assertEquals("unavailable", result.source());
        assertTrue(result.items().isEmpty());
        assertTrue(result.unavailable().contains("RuntimeException"));
        assertFalse(result.unavailable().contains("arn:"));
        assertFalse(result.unavailable().contains("secret"));
    }

    @Test
    void explicitUnavailableKeepsItsPublishableReason() {
        Stub stub = new Stub();
        stub.failure = new AwsReadUnavailableException("CI workflow snapshot is stale");
        ProviderResult result = new WorkflowSnapshotReadAdapter(stub, props()).workflows();
        assertEquals("unavailable", result.source());
        assertEquals("CI workflow snapshot is stale", result.unavailable());
    }

    @Test
    void forbiddenKeysAndAccountIdsAreStripped() {
        Stub stub = new Stub() {
            @Override
            public List<Map<String, Object>> resources() {
                return List.of(row(
                        "id", "album-processor", "status", "active",
                        "fullArn", "arn:aws:ecr:ap-southeast-1:123456789012:repository/album-processor",
                        "token", "not-a-real-token",
                        "password", "not-a-real-password",
                        "account", "156487532333"));
            }
        };
        Map<String, Object> clean = new AwsCloudReadAdapter(stub, props()).resources().items().get(0);
        assertFalse(clean.containsKey("fullArn"));
        assertFalse(clean.containsKey("token"));
        assertFalse(clean.containsKey("password"));
        assertEquals("[REDACTED]", clean.get("account"),
                "a numeric account id must never leave the service in clear text");
    }

    @Test
    void itemCapProtectsResponseSize() {
        Stub stub = new Stub() {
            @Override
            public List<Map<String, Object>> resources() {
                List<Map<String, Object>> rows = new ArrayList<>();
                for (int i = 0; i < 50; i++) {
                    rows.add(row("id", "r" + i, "status", "active"));
                }
                return rows;
            }
        };
        assertEquals(3, new AwsCloudReadAdapter(stub, props()).resources().items().size());
    }

    @Test
    void cloudFrontStatusNeverClaimsPlanned() {
        assertEquals("active", SdkAwsReadGateway.cloudFrontStatus("Deployed"));
        assertEquals("degraded", SdkAwsReadGateway.cloudFrontStatus("InProgress"));
        assertEquals("unknown", SdkAwsReadGateway.cloudFrontStatus(null));
        assertEquals("unknown", SdkAwsReadGateway.cloudFrontStatus("SomethingElse"));
        assertFalse("planned".equals(SdkAwsReadGateway.cloudFrontStatus("Deployed")));
    }

    @Test
    void structuredLogsKeepOnlyContractFields() {
        String message = "{\"event\":\"process.done\",\"correlationId\":\"corr_bb6f8616101611d0\","
                + "\"stage\":\"processor\",\"outcome\":\"success\",\"durationMs\":3037,"
                + "\"phone\":\"13800138000\",\"rawResponse\":\"should-not-appear\"}";
        FilteredLogEvent event = FilteredLogEvent.builder()
                .message(message)
                .logStreamName("ecs/album-processor")
                .timestamp(1794648889000L)
                .build();
        Map<String, Object> parsed = SdkAwsReadGateway.structuredLog(event, "/aws/lambda/album-processor");
        assertNotNull(parsed);
        assertEquals("process.done", parsed.get("event"));
        assertEquals("corr_bb6f8616101611d0", parsed.get("correlationId"));
        assertEquals(3037, parsed.get("durationMs"));
        assertFalse(parsed.containsKey("phone"));
        assertFalse(parsed.containsKey("rawResponse"));
    }

    @Test
    void rawTextLogLinesAreNeverExposed() {
        FilteredLogEvent event = FilteredLogEvent.builder()
                .message("2026-11-14 raw unstructured line that may contain PII")
                .logStreamName("ecs/album-processor")
                .timestamp(1794648889000L)
                .build();
        assertNull(SdkAwsReadGateway.structuredLog(event, "/aws/lambda/album-processor"));
    }

    // 真实指标：AWS/Lambda 指标必须按 FunctionName 查询，否则 CloudWatch 返回空样本。
    // Real metrics: AWS/Lambda needs a FunctionName dimension, otherwise samples are empty.
    @Test
    void metricQueriesTargetRealLambdaFunctionsWithDimensions() {
        MgmtAwsProperties props = props();
        List<MetricDataQuery> queries = SdkAwsReadGateway.metricQueries(props);
        assertEquals(6, queries.size(), "2 functions x 3 metrics");
        for (MetricDataQuery query : queries) {
            List<Dimension> dimensions = query.metricStat().metric().dimensions();
            assertEquals(1, dimensions.size());
            assertEquals("FunctionName", dimensions.get(0).name());
            assertTrue(props.boundedLambdaFunctions().contains(dimensions.get(0).value()));
            assertEquals("AWS/Lambda", query.metricStat().metric().namespace());
            assertTrue(query.returnData());
        }
    }

    @Test
    void emptyMetricSamplesNeverBecomeFakeZeroRows() {
        MgmtAwsProperties props = props();
        assertNull(SdkAwsReadGateway.metricRow(
                MetricDataResult.builder().id("q0").build(), props));
        assertNull(SdkAwsReadGateway.metricRow(
                MetricDataResult.builder().id("q0").values(List.<Double>of()).build(), props));
        assertNull(SdkAwsReadGateway.metricRow(
                MetricDataResult.builder().id("not-a-query").values(List.of(5.0)).build(), props));
    }

    @Test
    void metricRowsCarryRealValuesAndCorrectUnits() {
        MgmtAwsProperties props = props();
        Map<String, Object> invocations = SdkAwsReadGateway.metricRow(
                MetricDataResult.builder().id("q0").values(List.of(2.0, 3.0)).build(), props);
        assertNotNull(invocations);
        assertEquals("album-auth", invocations.get("function"));
        assertEquals("AWS/Lambda.Invocations", invocations.get("metric"));
        assertEquals("count", invocations.get("unit"));
        assertEquals(5.0, invocations.get("value"));
        assertEquals(2, invocations.get("samples"));

        // Errors 是计数，不是毫秒（旧实现把它错标为 milliseconds）
        // Errors is a count, not milliseconds (the old implementation mislabelled it).
        Map<String, Object> errors = SdkAwsReadGateway.metricRow(
                MetricDataResult.builder().id("q4").values(List.of(1.0)).build(), props);
        assertNotNull(errors);
        assertEquals("album-processor", errors.get("function"));
        assertEquals("AWS/Lambda.Errors", errors.get("metric"));
        assertEquals("count", errors.get("unit"));

        Map<String, Object> duration = SdkAwsReadGateway.metricRow(
                MetricDataResult.builder().id("q5").values(List.of(1234.0)).build(), props);
        assertNotNull(duration);
        assertEquals("milliseconds", duration.get("unit"));
    }

    @Test
    void metricQueriesAreEmptyWithoutConfiguredFunctions() {
        MgmtAwsProperties props = props();
        props.setLambdaFunctions(java.util.Arrays.asList("", null, "  album-auth ", "album-auth"));
        assertEquals(List.of("album-auth"), props.boundedLambdaFunctions(),
                "blank names are dropped and duplicates collapse");
        assertEquals(3, SdkAwsReadGateway.metricQueries(props).size());

        props.setLambdaFunctions(List.of());
        assertTrue(SdkAwsReadGateway.metricQueries(props).isEmpty());
    }
}
