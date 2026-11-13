package top.chenyouyi.album.mgmt.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * 契约 identity.roles 的角色到权限映射测试：
 * admin 全域可读、auditor 仅 audit+security、viewer 仅 resources+cicd，
 * 未知角色为空集，权限字符串逐域独立。
 *
 * Contract identity.roles mapping tests: admin reads every domain, auditor only
 * audit+security, viewer only resources+cicd, unknown roles get an empty set, and
 * permissions are independent per domain.
 */
class RolePermissionsTest {

    private static final Set<String> ALL_DOMAINS = Set.of(
            "cicd", "resources", "logs", "metrics", "security", "billing", "audit");

    @Test
    void adminCanReadEveryDomain() {
        for (String domain : ALL_DOMAINS) {
            assertTrue(RolePermissions.has("admin", domain + ":read"),
                    "admin 应可读域 " + domain + " / admin should read " + domain);
        }
    }

    @Test
    void auditorCanReadOnlyAuditAndSecurity() {
        assertTrue(RolePermissions.has("auditor", "audit:read"));
        assertTrue(RolePermissions.has("auditor", "security:read"));

        for (String domain : Set.of("cicd", "resources", "logs", "metrics", "billing")) {
            assertFalse(RolePermissions.has("auditor", domain + ":read"),
                    "auditor 不应读域 " + domain + " / auditor should NOT read " + domain);
        }
    }

    @Test
    void viewerCanReadOnlyResourcesAndCicd() {
        assertTrue(RolePermissions.has("viewer", "resources:read"));
        assertTrue(RolePermissions.has("viewer", "cicd:read"));

        for (String domain : Set.of("logs", "metrics", "security", "billing", "audit")) {
            assertFalse(RolePermissions.has("viewer", domain + ":read"),
                    "viewer 不应读域 " + domain + " / viewer should NOT read " + domain);
        }
    }

    @Test
    void unknownRoleHasNoPermissions() {
        assertEquals(Set.of(), RolePermissions.forRole("ghost"));
        assertFalse(RolePermissions.has("ghost", "resources:read"));
        assertFalse(RolePermissions.has(null, "resources:read"));
        assertFalse(RolePermissions.has("", "resources:read"));
    }

    @Test
    void permissionsArePerDomainNotWildcard() {
        // 权限是逐域的，不存在通配：拥有 billing:read 不代表拥有 audit:read。
        // Permissions are per-domain with no wildcard: billing:read does not imply audit:read.
        Set<String> admin = RolePermissions.forRole("admin");
        assertEquals(7, admin.size());
        for (String domain : ALL_DOMAINS) {
            assertTrue(admin.contains(domain + ":read"));
        }
        assertFalse(RolePermissions.has("viewer", "read"));
        assertFalse(RolePermissions.has("viewer", "*"));
    }
}
