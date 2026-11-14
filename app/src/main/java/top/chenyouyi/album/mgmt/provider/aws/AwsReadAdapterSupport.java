package top.chenyouyi.album.mgmt.provider.aws;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import top.chenyouyi.album.mgmt.common.CorrelationContext;
import top.chenyouyi.album.mgmt.common.ForbiddenFields;
import top.chenyouyi.album.mgmt.common.ProviderResult;

/**
 * 所有 cloud adapter 共用的读取包装：限流、递归脱敏、失败降级，并写一条符合
 * 契约 structuredLogEvent 的日志。上游异常文本绝不进入响应，只保留类名。
 *
 * Shared read wrapper for every cloud adapter: caps the item count, redacts
 * recursively, degrades honestly on failure, and emits one structured log event
 * matching the contract. Upstream exception text never reaches the response;
 * only the exception class name is kept.
 */
public final class AwsReadAdapterSupport {

    private static final Logger log = LoggerFactory.getLogger(AwsReadAdapterSupport.class);

    private AwsReadAdapterSupport() {
    }

    public static ProviderResult read(
            String domain,
            Supplier<List<Map<String, Object>>> call,
            MgmtAwsProperties props) {
        long started = System.nanoTime();
        ProviderResult result;
        String errorClass = "none";
        try {
            List<Map<String, Object>> raw = call.get();
            if (raw == null) {
                throw new AwsReadUnavailableException(domain + " read returned no data");
            }
            int cap = props.boundedMaxItems();
            List<Map<String, Object>> items = new ArrayList<>();
            for (Map<String, Object> row : raw) {
                if (items.size() >= cap) {
                    break;
                }
                if (row == null || row.isEmpty()) {
                    continue;
                }
                Map<String, Object> clean = ForbiddenFields.redactMap(new LinkedHashMap<>(row));
                if (!clean.isEmpty()) {
                    items.add(clean);
                }
            }
            result = ProviderResult.of("aws", items);
        } catch (AwsReadUnavailableException e) {
            errorClass = e.getClass().getSimpleName();
            result = ProviderResult.unavailable(e.getMessage());
        } catch (RuntimeException e) {
            errorClass = e.getClass().getSimpleName();
            result = ProviderResult.unavailable(domain + " read failed: " + errorClass);
        }
        trace(domain, result, errorClass, (System.nanoTime() - started) / 1_000_000L);
        return result;
    }

    /**
     * 结构化日志：event / correlationId / stage / outcome / durationMs 为契约必填字段。
     * 用单个占位符输出，避免 JSON 花括号被 SLF4J 当成参数标记。
     *
     * Structured log with the contract-required fields. A single placeholder keeps
     * SLF4J from treating JSON braces as argument markers.
     */
    private static void trace(String domain, ProviderResult result, String errorClass, long durationMs) {
        String outcome = result.unavailable() == null ? "success" : "unavailable";
        String payload = new StringBuilder(256)
                .append("{\"event\":\"management.read\",\"correlationId\":\"")
                .append(CorrelationContext.get())
                .append("\",\"stage\":\"management\",\"outcome\":\"").append(outcome)
                .append("\",\"durationMs\":").append(durationMs)
                .append(",\"provider\":\"aws\",\"errorClass\":\"").append(errorClass)
                .append("\",\"domain\":\"").append(domain)
                .append("\",\"itemCount\":").append(result.items().size())
                .append("}")
                .toString();
        log.info("{}", payload);
    }
}
