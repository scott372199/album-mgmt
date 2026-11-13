package top.chenyouyi.album.mgmt.security;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import top.chenyouyi.album.mgmt.common.ContractMetrics;
import top.chenyouyi.album.mgmt.common.CorrelationContext;
import top.chenyouyi.album.mgmt.common.CorrelationId;
import top.chenyouyi.album.mgmt.common.ForbiddenFields;
import top.chenyouyi.album.mgmt.provider.dto.GrantedIdentity;
import top.chenyouyi.album.mgmt.provider.port.AuditStore;

/**
 * 外层过滤器：解析/生成 correlationId（契约 correlationId），写回响应头，
 * 并在链路结束后为六域数据读取记录审计事件（契约 identity.auditEvent）。
 * 审计事件在写入前经 ForbiddenFields 递归过滤；who 是哈希派生主体，绝不落明文手机号。
 * 同时记录 management.api_latency_ms（契约 metrics）。
 *
 * Outermost filter: resolves or generates the correlationId (contract correlationId),
 * echoes it on the response, and records an audit event for each domain read after the
 * chain completes (contract identity.auditEvent). Events are redacted via ForbiddenFields
 * before storage; the actor is a hash-derived subject, never a plaintext phone. Also
 * records management.api_latency_ms (contract metrics).
 */
@Component
@Order(1)
public class CorrelationAuditFilter extends OncePerRequestFilter {

    private final AuditStore auditStore;
    private final MeterRegistry meterRegistry;

    public CorrelationAuditFilter(AuditStore auditStore, MeterRegistry meterRegistry) {
        this.auditStore = auditStore;
        this.meterRegistry = meterRegistry;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/v1/") || path.startsWith("/api/v1/auth/") || path.equals("/api/v1/health");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String correlationId = CorrelationId.resolve(request.getHeader(CorrelationId.HEADER));
        request.setAttribute(CorrelationId.REQUEST_ATTRIBUTE, correlationId);
        long startedAt = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
            response.setHeader(CorrelationId.HEADER, correlationId);
            meterRegistry.timer(ContractMetrics.API_LATENCY_MS).record(durationMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            recordAudit(request, response, correlationId, durationMs);
        }
    }

    private void recordAudit(HttpServletRequest request, HttpServletResponse response, String correlationId, long durationMs) {
        Object identity = request.getAttribute(DomainSecurityFilter.IDENTITY_ATTRIBUTE);
        String who = identity instanceof GrantedIdentity granted
                ? granted.subjectHash()
                : "anonymous";

        int status = response.getStatus();
        String outcome = status < 300 ? "allow" : (status == 401 || status == 403 ? "deny" : "failed");

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("who", who);
        event.put("when", Instant.now().toString());
        event.put("domain", domain(request.getRequestURI()));
        event.put("action", "read");
        event.put("correlationId", correlationId);
        event.put("outcome", outcome);
        event.put("durationMs", durationMs);

        // 契约要求写前过滤；即使未来加入新字段也不会泄漏敏感键。
        // Redact before recording so future fields can never leak sensitive keys.
        auditStore.record(ForbiddenFields.redactMap(event));
        CorrelationContext.get(); // 保持与请求上下文一致 / keep the request context aligned
    }

    static String domain(String path) {
        String[] parts = path.split("/");
        return parts.length > 3 ? parts[3] : "";
    }
}
