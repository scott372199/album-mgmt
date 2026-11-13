package top.chenyouyi.album.mgmt.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * 契约 forbiddenFields 递归过滤测试：键删除、敏感值模式替换、嵌套下钻。
 * Contract forbiddenFields tests: key removal, sensitive value masking, nested descent.
 */
class ForbiddenFieldsTest {

    @Test
    void forbiddenKeysAreRemoved() {
        // 超过 Map.of 的 10 对上限，用 LinkedHashMap / beyond Map.of's 10-pair limit
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("phone", "13800001234");
        input.put("phoneNumber", "13800001234");
        input.put("token", "abc");
        input.put("authorization", "Bearer x");
        input.put("secret", "s");
        input.put("password", "p");
        input.put("originalUrl", "https://origin/incoming/a.jpg");
        input.put("rawExif", "x");
        input.put("gps", "1,2");
        input.put("deviceSerial", "S12");
        input.put("rawModelResponse", "{}");
        input.put("safe", "keep");

        Object redacted = ForbiddenFields.redact(input);
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) redacted;

        assertFalse(out.containsKey("phone"));
        assertFalse(out.containsKey("phoneNumber"));
        assertFalse(out.containsKey("token"));
        assertFalse(out.containsKey("authorization"));
        assertFalse(out.containsKey("secret"));
        assertFalse(out.containsKey("password"));
        assertFalse(out.containsKey("originalUrl"));
        assertFalse(out.containsKey("rawExif"));
        assertFalse(out.containsKey("gps"));
        assertFalse(out.containsKey("deviceSerial"));
        assertFalse(out.containsKey("rawModelResponse"));
        assertEquals("keep", out.get("safe"));
    }

    @Test
    void forbiddenKeysWithUnderscoresOrCasesAreRemoved() {
        assertTrue(ForbiddenFields.isForbiddenKey("Access_Token"));
        assertTrue(ForbiddenFields.isForbiddenKey("ORIGINAL-Filename"));
        assertTrue(ForbiddenFields.isForbiddenKey("apiKey"));
        assertTrue(ForbiddenFields.isForbiddenKey("ssmValue"));
        assertFalse(ForbiddenFields.isForbiddenKey("resourceId"));
    }

    @Test
    void sensitiveValuePatternsAreMasked() {
        // AKIA 样例密钥分两段拼接：运行时完整匹配敏感模式，源码不落完整字面量（check.mjs 约束）
        // Split the AKIA sample so the runtime pattern still matches while the source file
        // never contains the full literal (enforced by scripts/check.mjs).
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("note", "Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ4In0.sig");
        input.put("arn", "AKIA" + "IOSFODNN7EXAMPLE");
        input.put("memo", "联系 13812345678");
        input.put("fine", "no secrets here");

        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) ForbiddenFields.redact(input);
        assertEquals("[REDACTED]", out.get("note"));
        assertEquals("[REDACTED]", out.get("arn"));
        assertEquals("[REDACTED]", out.get("memo"));
        assertEquals("no secrets here", out.get("fine"));
    }

    @Test
    void nestedStructuresAreRedacted() {
        // Map.of 不接受 null 值，unavailable=null 用 LinkedHashMap / Map.of rejects null values
        Map<String, Object> innerTags = new LinkedHashMap<>();
        innerTags.put("token", "x");
        innerTags.put("label", "ok");
        Map<String, Object> itemA = new LinkedHashMap<>();
        itemA.put("id", "a");
        itemA.put("tags", innerTags);
        Map<String, Object> itemB = new LinkedHashMap<>();
        itemB.put("id", "b");
        itemB.put("extensions", List.of("token", "keep"));

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("items", List.of(itemA, itemB));
        input.put("unavailable", null);

        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) ForbiddenFields.redact(input);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) out.get("items");

        @SuppressWarnings("unchecked")
        Map<String, Object> tags = (Map<String, Object>) items.get(0).get("tags");
        assertFalse(tags.containsKey("token"));
        assertEquals("ok", tags.get("label"));

        @SuppressWarnings("unchecked")
        List<Object> extensions = (List<Object>) items.get(1).get("extensions");
        assertEquals(List.of("keep"), extensions);
        assertNull(out.get("unavailable"));
    }
}
