package top.chenyouyi.album.mgmt.common;

import java.util.List;
import java.util.Map;

import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 出站兜底过滤：所有 @RestController 的 JSON 响应在序列化前经 ForbiddenFields 递归过滤，
 * 即使未来某个 controller 忘了过滤也不会泄漏敏感键或敏感值模式。
 * 例外：/api/v1/auth/** 的响应是凭证本身（accessToken/refreshToken），必须原样返回客户端，
 * 否则登录失效；这些端点不返回任何数据类内容，放行是安全边界的一部分。
 *
 * Outbound defence in depth: every @RestController JSON body passes through the recursive
 * ForbiddenFields filter before serialization, so a future controller cannot leak sensitive
 * keys or value patterns even by accident. Exception: /api/v1/auth/** responses ARE the
 * credentials (accessToken/refreshToken) and must reach the client verbatim; those endpoints
 * return no data content, so letting them through is part of the boundary.
 */
@RestControllerAdvice
public class ResponseRedactionAdvice implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(MethodParameter returnType, Class converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(
            Object body,
            MethodParameter returnType,
            MediaType selectedContentType,
            Class selectedConverterType,
            ServerHttpRequest request,
            ServerHttpResponse response) {

        if (!(body instanceof Map) && !(body instanceof List)) {
            return body;
        }
        HttpServletRequest servletRequest = currentRequest();
        if (servletRequest != null && servletRequest.getRequestURI().startsWith("/api/v1/auth/")) {
            return body; // 凭证端点放行 / credential endpoints pass through
        }
        return ForbiddenFields.redact(body);
    }

    private HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest();
        }
        return null;
    }
}
