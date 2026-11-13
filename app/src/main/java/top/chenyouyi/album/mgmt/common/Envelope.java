package top.chenyouyi.album.mgmt.common;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 契约 managementApi.commonEnvelope：items/source/asOf/correlationId/unavailable。
 * 所有六域只读 API 统一返回本结构（经 forbiddenFields 递归过滤后写出）。
 *
 * Contract managementApi.commonEnvelope: items/source/asOf/correlationId/unavailable.
 * Every read-only domain API returns this shape, after recursive forbidden-field redaction.
 */
public record Envelope(
        List<Map<String, Object>> items,
        String source,
        String asOf,
        String correlationId,
        String unavailable) {

    public static Envelope from(ProviderResult result, String correlationId) {
        return new Envelope(
                result.items(),
                result.source(),
                Instant.now().toString(),
                correlationId,
                result.unavailable());
    }

    /** 转为可递归过滤的 Map / Convert to a Map so redaction can walk it recursively. */
    public Map<String, Object> asMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("items", items);
        map.put("source", source);
        map.put("asOf", asOf);
        map.put("correlationId", correlationId);
        map.put("unavailable", unavailable);
        return map;
    }
}
