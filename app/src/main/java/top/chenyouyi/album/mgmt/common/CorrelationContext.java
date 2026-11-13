package top.chenyouyi.album.mgmt.common;

import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * 读取当前请求在 CorrelationAuditFilter 中生成的 correlationId。
 * 供 Controller 组装 commonEnvelope 使用。
 *
 * Reads the correlationId set by CorrelationAuditFilter for the current request,
 * used by controllers to build the common envelope.
 */
public final class CorrelationContext {

    private CorrelationContext() {
    }

    public static String get() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs != null) {
            Object value = attrs.getAttribute(CorrelationId.REQUEST_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
            if (value instanceof String s) {
                return s;
            }
        }
        return CorrelationId.generate();
    }
}
