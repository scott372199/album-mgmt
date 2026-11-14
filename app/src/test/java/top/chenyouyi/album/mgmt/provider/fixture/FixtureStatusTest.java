package top.chenyouyi.album.mgmt.provider.fixture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * V5 release blocker #6：只读快照不得把 ACTIVE / Deployed 的资源报成 planned。
 * fixture 没有任何真实清单权限，未经验证的资源只能报 unknown。
 *
 * V5 blocker #6: a read-only snapshot must not report ACTIVE / Deployed
 * resources as planned. Fixtures have no inventory permission, so anything
 * unverified must be reported as unknown.
 */
class FixtureStatusTest {

    /** 契约 managementApi.resourceDto.status 枚举 / contract status enum. */
    private static final Set<String> CONTRACT_STATUSES =
            Set.of("active", "degraded", "planned", "unknown");

    @Test
    void everyStatusStaysInsideTheContractEnum() {
        for (Map<String, Object> resource : FixtureData.resources()) {
            String status = String.valueOf(resource.get("status"));
            assertTrue(CONTRACT_STATUSES.contains(status),
                    "status must be a contract value, got: " + status);
        }
    }

    @Test
    void runtimeResourcesNeverClaimPlannedOrActive() {
        List<Map<String, Object>> runtime = FixtureData.resources().stream()
                .filter(resource -> String.valueOf(resource.get("id")).startsWith("mgmt-"))
                .toList();

        assertEquals(2, runtime.size(), "ECS Express and the cx distribution are the two runtime rows");
        for (Map<String, Object> resource : runtime) {
            assertEquals("unknown", resource.get("status"),
                    "unverified runtime must report unknown: " + resource.get("id"));
        }
    }

    @Test
    void activeIsReservedForTheStaticallyVerifiableRepository() {
        List<Map<String, Object>> active = FixtureData.resources().stream()
                .filter(resource -> "active".equals(resource.get("status")))
                .toList();
        assertEquals(1, active.size());
        assertEquals("album-mgmt", active.get(0).get("id"));
        assertEquals("ecr", active.get(0).get("type"));
    }
}
