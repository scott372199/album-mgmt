package top.chenyouyi.album.mgmt.common;

import java.util.List;

/**
 * 契约 metrics 列表（v5-contracts.json）。供 Micrometer 注册与 metrics 域目录共用，
 * 避免两处清单漂移。/ Contract metrics list, shared by Micrometer registration and the
 * metrics domain catalog to avoid drift.
 */
public final class ContractMetrics {

    public static final String API_LATENCY_MS = "management.api_latency_ms";
    public static final String API_5XX_RATE = "management.api_5xx_rate";

    public static final List<String> ALL = List.of(
            "upload.success_rate",
            "upload.error_rate",
            "processor.duration_ms",
            "processor.error_rate",
            "recognition.duration_ms",
            "recognition.success_rate",
            "recognition.retry_count",
            "recognition.queue_age_ms",
            "dlq.depth",
            "dlq.oldest_age_ms",
            "manifest.publish_conflict",
            "manifest.publish_retry",
            "cicd.workflow_failure",
            "cicd.deploy_duration_ms",
            API_LATENCY_MS,
            API_5XX_RATE,
            "cost.budget_threshold",
            "cost.anomaly");

    private ContractMetrics() {
    }
}
