package top.chenyouyi.album.mgmt.provider.aws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * CI workflow 快照的解析与防伪造测试：无 S3 client、无网络。
 * 快照是管理站 CI/CD 域唯一的真实数据源，因此 schema、新鲜度与字段白名单
 * 都必须严格校验。
 *
 * Parsing and anti-forgery tests for the CI workflow snapshot: no S3 client and
 * no network. The snapshot is the CI/CD domain's only real data source, so its
 * schema, freshness and field whitelist are strictly validated.
 */
class WorkflowSnapshotTest {

    private static final String SCHEMA = "album.cicd-snapshot.v1";

    private static MgmtAwsProperties props() {
        MgmtAwsProperties props = new MgmtAwsProperties();
        props.setSnapshotBucket("mgmt-data");
        props.setSnapshotKey("cicd/workflows.json");
        props.setSnapshotMaxAgeMinutes(180);
        props.setMaxItems(10);
        return props;
    }

    private static SdkAwsReadGateway gateway(MgmtAwsProperties props, String body) {
        return new SdkAwsReadGateway(AwsSdkClients.empty(), props, request -> body);
    }

    private static String snapshot(String generatedAt, String runsJson) {
        return "{\"schema\":\"" + SCHEMA + "\",\"generatedAt\":\"" + generatedAt
                + "\",\"repositories\":[\"scott372199/album\"],\"runs\":" + runsJson + "}";
    }

    private static String runs() {
        return "[{\"repository\":\"scott372199/album\",\"branch\":\"main\","
                + "\"name\":\"build-image\",\"status\":\"completed\",\"conclusion\":\"success\","
                + "\"commitDigest\":\"bd9d122\",\"runId\":43582454660,\"startedAt\":\"2026-11-14T10:00:00Z\","
                + "\"token\":\"not-a-real-token\",\"htmlUrl\":\"https://example.invalid/run/1\"}]";
    }

    @Test
    void validSnapshotIsMappedToContractFieldsOnly() {
        String body = snapshot(Instant.now().toString(), runs());
        List<Map<String, Object>> items = gateway(props(), body).workflows();
        assertEquals(1, items.size());
        Map<String, Object> row = items.get(0);
        assertEquals("scott372199/album", row.get("repository"));
        assertEquals("build-image", row.get("name"));
        assertEquals("success", row.get("conclusion"));
        assertFalse(row.containsKey("token"), "snapshot extra fields must not leak");
        assertFalse(row.containsKey("htmlUrl"));
    }

    @Test
    void staleSnapshotIsRejectedInsteadOfShownAsFresh() {
        String old = Instant.now().minus(1, ChronoUnit.DAYS).toString();
        AwsReadUnavailableException error = assertThrows(
                AwsReadUnavailableException.class,
                () -> gateway(props(), snapshot(old, runs())).workflows());
        assertTrue(error.getMessage().contains("stale"));
    }

    @Test
    void unsupportedSchemaIsRejected() {
        String body = "{\"schema\":\"other.v9\",\"generatedAt\":\"" + Instant.now()
                + "\",\"runs\":" + runs() + "}";
        AwsReadUnavailableException error = assertThrows(
                AwsReadUnavailableException.class,
                () -> gateway(props(), body).workflows());
        assertTrue(error.getMessage().contains("schema"));
    }

    @Test
    void malformedJsonIsRejected() {
        assertThrows(AwsReadUnavailableException.class,
                () -> gateway(props(), "{not json").workflows());
    }

    @Test
    void emptyRunsAreRejected() {
        assertThrows(AwsReadUnavailableException.class,
                () -> gateway(props(), snapshot(Instant.now().toString(), "[]")).workflows());
    }

    @Test
    void missingBucketDegradesHonestly() {
        MgmtAwsProperties props = props();
        props.setSnapshotBucket("");
        assertThrows(AwsReadUnavailableException.class,
                () -> gateway(props, snapshot(Instant.now().toString(), runs())).workflows());
    }

    @Test
    void readerFailureNeverLeaksTheUpstreamMessage() {
        SdkAwsReadGateway gateway = new SdkAwsReadGateway(AwsSdkClients.empty(), props(), request -> {
            throw new IllegalStateException("internal bucket path / private detail");
        });
        AwsReadUnavailableException error = assertThrows(
                AwsReadUnavailableException.class, gateway::workflows);
        assertFalse(error.getMessage().contains("private detail"));
    }

    @Test
    void freshnessBoundaryIsInclusive() {
        MgmtAwsProperties props = props();
        String fresh = Instant.now().minus(179, ChronoUnit.MINUTES).toString();
        assertEquals(1, gateway(props, snapshot(fresh, runs())).workflows().size());
        String expired = Instant.now().minus(181, ChronoUnit.MINUTES).toString();
        assertThrows(AwsReadUnavailableException.class,
                () -> gateway(props, snapshot(expired, runs())).workflows());
    }

    @Test
    void itemCapAppliesToSnapshotRuns() {
        MgmtAwsProperties props = props();
        props.setMaxItems(1);
        String two = "[" + runs().substring(1, runs().length() - 1) + ","
                + runs().substring(1, runs().length() - 1) + "]";
        assertEquals(1, gateway(props, snapshot(Instant.now().toString(), two)).workflows().size());
    }
}
