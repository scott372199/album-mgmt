package top.chenyouyi.album.mgmt.identity;

import java.util.Map;
import java.util.Set;

/**
 * 契约 identity.roles 的角色到逐域权限映射。
 * 每个域拥有独立权限字符串（如 cicd:read），逐域校验。
 *
 * Contract identity.roles mapping. Each domain has its own permission string
 * (e.g. cicd:read) and is checked independently.
 */
public final class RolePermissions {

    private static final Map<String, Set<String>> ROLES = Map.of(
            "admin", Set.of(
                    "cicd:read", "resources:read", "logs:read", "metrics:read",
                    "security:read", "billing:read", "audit:read"),
            "auditor", Set.of("audit:read", "security:read"),
            "viewer", Set.of("resources:read", "cicd:read"));

    private RolePermissions() {
    }

    public static Set<String> forRole(String role) {
        // Map.of 不接受 null 键，未知/null 角色统一空集 / Map.of rejects null keys; unknown/null → empty
        return role == null ? Set.of() : ROLES.getOrDefault(role, Set.of());
    }

    public static boolean has(String role, String permission) {
        return forRole(role).contains(permission);
    }
}
