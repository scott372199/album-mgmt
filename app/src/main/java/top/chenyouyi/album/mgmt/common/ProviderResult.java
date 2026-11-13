package top.chenyouyi.album.mgmt.common;

import java.util.List;
import java.util.Map;

/**
 * Provider 返回的统一结果：items 为 provider-neutral DTO 列表，
 * source 取 aws|github|fixture|unavailable，unavailable 为不可用原因（可用时为 null）。
 * adapter 不可用时必须返回 source=unavailable + 原因，绝不伪造数据。
 *
 * Unified provider result: items are provider-neutral DTOs, source is one of
 * aws|github|fixture|unavailable, and unavailable carries the reason (null when available).
 * An unavailable adapter must return source=unavailable with a reason and never fake data.
 */
public record ProviderResult(
        List<Map<String, Object>> items,
        String source,
        String unavailable) {

    public ProviderResult {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public static ProviderResult fixture(List<Map<String, Object>> items) {
        return new ProviderResult(items, "fixture", null);
    }

    public static ProviderResult of(String source, List<Map<String, Object>> items) {
        return new ProviderResult(items, source, null);
    }

    public static ProviderResult unavailable(String reason) {
        return new ProviderResult(List.of(), "unavailable", reason);
    }
}
