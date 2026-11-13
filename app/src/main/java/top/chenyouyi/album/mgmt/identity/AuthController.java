package top.chenyouyi.album.mgmt.identity;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import top.chenyouyi.album.mgmt.identity.AuthDtos.LoginRequest;
import top.chenyouyi.album.mgmt.identity.AuthDtos.RefreshRequest;
import top.chenyouyi.album.mgmt.provider.dto.GrantedIdentity;
import top.chenyouyi.album.mgmt.provider.port.IdentityProvider;

/**
 * 契约 managementApi.auth：POST /api/v1/auth/login|refresh|logout。
 * login 用 IdentityProvider（白名单 + scrypt）校验，签发独立 MGMT_JWT_SECRET 令牌；
 * 响应体是认证凭证本身，故由 ResponseRedactionAdvice 放行（数据类响应仍递归过滤）。
 * logout 为无状态：客户端丢弃令牌即可，服务端不维护会话。
 *
 * Contract managementApi.auth. Login verifies via IdentityProvider (allowlist + scrypt)
 * and issues tokens with the independent MGMT_JWT_SECRET. The response is the credential
 * itself, so ResponseRedactionAdvice lets it through (data responses are still redacted).
 * Logout is stateless: the client discards tokens; no server session is kept.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final IdentityProvider identityProvider;
    private final JwtService jwtService;
    private final JwtProperties jwtProperties;

    public AuthController(IdentityProvider identityProvider, JwtService jwtService, JwtProperties jwtProperties) {
        this.identityProvider = identityProvider;
        this.jwtService = jwtService;
        this.jwtProperties = jwtProperties;
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody LoginRequest request) {
        Optional<GrantedIdentity> granted = identityProvider.authenticate(request.principal(), request.password());
        if (granted.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "invalid_credentials"));
        }
        return ResponseEntity.ok(tokenBody(granted.get()));
    }

    @PostMapping("/refresh")
    public ResponseEntity<Map<String, Object>> refresh(@RequestBody RefreshRequest request) {
        Optional<JwtService.Claims> claims = jwtService.verify(request.refreshToken(), JwtService.TokenType.REFRESH);
        if (claims.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "invalid_refresh_token"));
        }
        JwtService.Claims c = claims.get();
        return ResponseEntity.ok(tokenBody(new GrantedIdentity(c.subjectHash(), c.role())));
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout() {
        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "message", "stateless logout: discard tokens client-side"));
    }

    private Map<String, Object> tokenBody(GrantedIdentity identity) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accessToken", jwtService.issue(identity.subjectHash(), identity.role(), JwtService.TokenType.ACCESS));
        body.put("refreshToken", jwtService.issue(identity.subjectHash(), identity.role(), JwtService.TokenType.REFRESH));
        body.put("tokenType", "Bearer");
        body.put("expiresIn", jwtProperties.accessTtlSeconds());
        body.put("role", identity.role());
        return body;
    }
}
