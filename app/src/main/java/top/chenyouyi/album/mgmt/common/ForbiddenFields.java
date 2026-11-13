package top.chenyouyi.album.mgmt.common;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 契约 forbiddenFields 递归过滤：手机号、令牌、密钥、原图 URL、raw response 等一概不写。
 * 同时覆盖 observability.forbiddenFields 与 managementApi.forbiddenInResponse 的键名。
 * 用于 (1) 审计/结构化日志写出前、(2) HTTP 响应序列化前的兜底过滤。
 *
 * Recursive redaction of contract forbiddenFields: phone, tokens, secrets, original URLs,
 * raw responses, etc. Applies before writing audit/structured logs and before serializing
 * HTTP responses (defence in depth).
 */
public final class ForbiddenFields {

    /** 归一化键名（小写、去掉 _ 与 -）/ Normalised key names (lowercase, no _ or -). */
    private static final Set<String> FORBIDDEN_KEYS = new LinkedHashSet<>(List.of(
            // v5-contracts.json forbiddenFields
            "accesskey", "authorization", "capturetimestamp", "deviceserial", "gps",
            "originalfilename", "originalkey", "originalurl", "password", "phone",
            "phonenumber", "rawexif", "rawmodelresponse", "rawrekognitionresponse",
            "refreshtoken", "secret", "sourcepath", "terraformstate", "token",
            // 额外防御 / extra defence-in-depth
            "accesstoken", "apikey", "privatekey", "ssmvalue", "rawresponse", "bearer",
            "credential", "fullarn"));

    /** 敏感值模式：JWT / Bearer / AKIA / 私钥 / 明文手机号 / Sensitive value patterns. */
    private static final Pattern SENSITIVE_VALUE = Pattern.compile(
            "(?i)(" +
                    "bearer\\s+[A-Za-z0-9._~+/=-]{8,}" +
                    "|eyJ[A-Za-z0-9_-]{6,}\\.[A-Za-z0-9_-]{6,}\\.[A-Za-z0-9_-]{6,}" +
                    "|AKIA[0-9A-Z]{16}" +
                    "|BEGIN (?:RSA |EC |OPENSSH |PGP )?PRIVATE KEY" +
                    "|1[3-9]\\d{9}" +
                    ")");

    private ForbiddenFields() {
    }

    public static boolean isForbiddenKey(String key) {
        if (key == null) {
            return false;
        }
        String normalised = key.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        return FORBIDDEN_KEYS.contains(normalised);
    }

    /**
     * 递归过滤：删除 forbidden 键，命中敏感值模式的字符串整段替换，Map/List 递归下钻。
     * Recursively drop forbidden keys, mask strings matching sensitive patterns,
     * and descend into maps and lists.
     */
    public static Object redact(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (isForbiddenKey(key)) {
                    continue; // 一概不写 / never written
                }
                out.put(key, redact(entry.getValue()));
            }
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object element : list) {
                // 列表元素本身是 forbidden 键名（如键名清单）同样不写 / a bare forbidden key name
                // inside a list (e.g. a key-name list) must not be written either
                if (element instanceof String s && isForbiddenKey(s)) {
                    continue;
                }
                out.add(redact(element));
            }
            return out;
        }
        if (value instanceof Object[] array) {
            List<Object> out = new ArrayList<>(array.length);
            for (Object element : array) {
                out.add(redact(element));
            }
            return out;
        }
        if (value instanceof String text && SENSITIVE_VALUE.matcher(text).find()) {
            return "[REDACTED]";
        }
        return value;
    }

    /** Map 版过滤（审计事件等固定 Map 场景）/ Map-typed variant for fixed-shape maps like audit events. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> redactMap(Map<String, Object> value) {
        return (Map<String, Object>) redact((Object) value);
    }
}
