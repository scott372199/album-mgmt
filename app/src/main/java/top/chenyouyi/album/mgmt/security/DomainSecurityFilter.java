package top.chenyouyi.album.mgmt.security;

import java.io.IOException;
import java.util.Map;

import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import top.chenyouyi.album.mgmt.identity.JwtService;
import top.chenyouyi.album.mgmt.identity.RolePermissions;
import top.chenyouyi.album.mgmt.provider.dto.GrantedIdentity;

/**
 * 认证与逐域授权过滤器（不用 Spring Security，零新增依赖）。
 * 规则：
 * 1. /api/v1/auth/** 与 /api/v1/health 公开；
 * 2. 其余 /api/v1/** 必须携带合法的 Bearer access 令牌（HS256、未过期、类型匹配），否则 401；
 * 3. 域权限逐域校验（契约 identity.roles，如 cicd:read），越权 403。
 * 通过后把 GrantedIdentity 放入请求属性，供审计与响应组装使用。
 *
 * Authentication and per-domain authorization filter (no Spring Security, zero extra deps).
 * Rules: /api/v1/auth/** and /api/v1/health are public; every other /api/v1/** path needs a
 * valid Bearer access token (HS256, unexpired, correct type) or returns 401; domain
 * permissions are checked per domain (contract identity.roles) and violations return 403.
 * The GrantedIdentity is exposed as a request attribute for audit and envelope assembly.
 */
@Component
@Order(2)
public class DomainSecurityFilter extends OncePerRequestFilter {

    public static final String IDENTITY_ATTRIBUTE = "mgmt.identity";

    private final JwtService jwtService;
    private final ObjectMapper objectMapper;

    public DomainSecurityFilter(JwtService jwtService, ObjectMapper objectMapper) {
        this.jwtService = jwtService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/v1/") || path.startsWith("/api/v1/auth/") || path.equals("/api/v1/health");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            deny(response, HttpStatus.UNAUTHORIZED.value(), "missing_bearer_token");
            return;
        }
        String token = header.substring("Bearer ".length()).trim();

        var claims = jwtService.verify(token, JwtService.TokenType.ACCESS);
        if (claims.isEmpty()) {
            deny(response, HttpStatus.UNAUTHORIZED.value(), "invalid_access_token");
            return;
        }

        String domain = CorrelationAuditFilter.domain(request.getRequestURI());
        if (domain.isBlank() || !RolePermissions.has(claims.get().role(), domain + ":read")) {
            deny(response, HttpStatus.FORBIDDEN.value(), "domain_permission_denied");
            return;
        }

        request.setAttribute(IDENTITY_ATTRIBUTE,
                new GrantedIdentity(claims.get().subjectHash(), claims.get().role()));
        chain.doFilter(request, response);
    }

    private void deny(HttpServletResponse response, int status, String error) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(Map.of("error", error)));
    }
}
