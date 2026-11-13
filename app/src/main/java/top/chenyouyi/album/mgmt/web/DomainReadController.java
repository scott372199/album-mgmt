package top.chenyouyi.album.mgmt.web;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import top.chenyouyi.album.mgmt.common.CorrelationContext;
import top.chenyouyi.album.mgmt.common.Envelope;
import top.chenyouyi.album.mgmt.provider.port.AuditStore;
import top.chenyouyi.album.mgmt.provider.port.BillingProvider;
import top.chenyouyi.album.mgmt.provider.port.CicdProvider;
import top.chenyouyi.album.mgmt.provider.port.CloudProvider;
import top.chenyouyi.album.mgmt.provider.port.LogProvider;
import top.chenyouyi.album.mgmt.provider.port.MetricProvider;
import top.chenyouyi.album.mgmt.provider.port.SecurityProvider;

/**
 * 契约 managementApi.endpoints 的六域只读 API + audit。
 * 每个端点返回统一 commonEnvelope（items/source/asOf/correlationId/unavailable），
 * 权限校验在 DomainSecurityFilter 完成（逐域 <domain>:read）。
 * adapter 不可用时来自 ProviderResult（source=unavailable + 原因），绝不伪造数据。
 *
 * The six read-only domain APIs plus audit from contract managementApi.endpoints.
 * Every endpoint returns the common envelope (items/source/asOf/correlationId/unavailable);
 * authorization happens in DomainSecurityFilter via per-domain <domain>:read checks.
 * Unavailable adapters come through ProviderResult (source=unavailable with a reason) —
 * data is never fabricated.
 */
@RestController
@RequestMapping("/api/v1")
public class DomainReadController {

    private final CicdProvider cicdProvider;
    private final CloudProvider cloudProvider;
    private final LogProvider logProvider;
    private final MetricProvider metricProvider;
    private final SecurityProvider securityProvider;
    private final BillingProvider billingProvider;
    private final AuditStore auditStore;

    public DomainReadController(
            CicdProvider cicdProvider,
            CloudProvider cloudProvider,
            LogProvider logProvider,
            MetricProvider metricProvider,
            SecurityProvider securityProvider,
            BillingProvider billingProvider,
            AuditStore auditStore) {
        this.cicdProvider = cicdProvider;
        this.cloudProvider = cloudProvider;
        this.logProvider = logProvider;
        this.metricProvider = metricProvider;
        this.securityProvider = securityProvider;
        this.billingProvider = billingProvider;
        this.auditStore = auditStore;
    }

    @GetMapping("/cicd/workflows")
    public Map<String, Object> workflows() {
        return Envelope.from(cicdProvider.workflows(), CorrelationContext.get()).asMap();
    }

    @GetMapping("/resources")
    public Map<String, Object> resources() {
        return Envelope.from(cloudProvider.resources(), CorrelationContext.get()).asMap();
    }

    @GetMapping("/logs")
    public Map<String, Object> logs() {
        return Envelope.from(logProvider.events(), CorrelationContext.get()).asMap();
    }

    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        return Envelope.from(metricProvider.series(), CorrelationContext.get()).asMap();
    }

    @GetMapping("/security")
    public Map<String, Object> security() {
        return Envelope.from(securityProvider.controls(), CorrelationContext.get()).asMap();
    }

    @GetMapping("/billing")
    public Map<String, Object> billing() {
        return Envelope.from(billingProvider.records(), CorrelationContext.get()).asMap();
    }

    @GetMapping("/audit")
    public Map<String, Object> audit() {
        return Envelope.from(auditStore.query(), CorrelationContext.get()).asMap();
    }
}
