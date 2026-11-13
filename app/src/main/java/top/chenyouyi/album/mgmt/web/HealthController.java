package top.chenyouyi.album.mgmt.web;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 公开健康端点（契约 managementApi：permission=none）。
 * 不经过 DomainSecurityFilter；correlationId 仍由 CorrelationAuditFilter 处理。
 *
 * Public health endpoint (contract managementApi: permission=none). It bypasses
 * DomainSecurityFilter; correlationId is still handled by CorrelationAuditFilter.
 */
@RestController
public class HealthController {

    @GetMapping("/api/v1/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "ok",
                "service", "album-mgmt",
                "mode", "read-only");
    }
}
