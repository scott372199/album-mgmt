package top.chenyouyi.album.mgmt.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * JWT 语义测试：类型、过期、签名、alg:none 拒绝与密钥独立性。
 * JWT semantics: type, expiry, signature, alg:none rejection and key isolation.
 */
class JwtServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JwtService service(String secret) {
        // 直接构造，避免引入 Spring 上下文 / construct directly to avoid a Spring context
        return new JwtService(new JwtProperties(secret, 3600, 604800), mapper);
    }

    @Test
    void accessAndRefreshTokensAreTyped() {
        JwtService service = service("secret-A");
        String access = service.issue("subject-hash", "admin", JwtService.TokenType.ACCESS);
        String refresh = service.issue("subject-hash", "admin", JwtService.TokenType.REFRESH);

        assertTrue(service.verify(access, JwtService.TokenType.ACCESS).isPresent());
        assertTrue(service.verify(refresh, JwtService.TokenType.REFRESH).isPresent());
        assertFalse(service.verify(access, JwtService.TokenType.REFRESH).isPresent());
        assertFalse(service.verify(refresh, JwtService.TokenType.ACCESS).isPresent());
    }

    @Test
    void expiredTokenIsRejected() {
        JwtService shortLived = new JwtService(new JwtProperties("secret-A", -1, 3600), mapper);
        String token = shortLived.issue("subject-hash", "admin", JwtService.TokenType.ACCESS);
        assertTrue(service("secret-A").verify(token, JwtService.TokenType.ACCESS).isEmpty());
    }

    @Test
    void tamperedSignatureIsRejected() {
        JwtService service = service("secret-A");
        String token = service.issue("subject-hash", "admin", JwtService.TokenType.ACCESS);
        String[] parts = token.split("\\.", -1);
        String forgedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"sub\":\"attacker\",\"role\":\"admin\",\"typ\":\"access\",\"exp\":9999999999}"
                        .getBytes(StandardCharsets.UTF_8));
        String forged = parts[0] + "." + forgedPayload + "." + parts[2];
        assertTrue(service.verify(forged, JwtService.TokenType.ACCESS).isEmpty());
    }

    @Test
    void algNoneIsRejected() {
        JwtService service = service("secret-A");
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"sub\":\"x\",\"role\":\"admin\",\"typ\":\"access\",\"exp\":9999999999}"
                        .getBytes(StandardCharsets.UTF_8));
        String algNone = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8))
                + "." + payload + ".";
        assertTrue(service.verify(algNone, JwtService.TokenType.ACCESS).isEmpty());
    }

    @Test
    void secretsAreIndependent() {
        String tokenA = service("secret-A").issue("subject-hash", "admin", JwtService.TokenType.ACCESS);
        // 用不同密钥签的服务无法校验 A 的令牌 / a service with another key cannot verify A's token
        assertTrue(service("secret-B").verify(tokenA, JwtService.TokenType.ACCESS).isEmpty());
    }

    @Test
    void blankOrMalformedTokensAreRejected() {
        JwtService service = service("secret-A");
        assertTrue(service.verify(null, JwtService.TokenType.ACCESS).isEmpty());
        assertTrue(service.verify("", JwtService.TokenType.ACCESS).isEmpty());
        assertTrue(service.verify("not-a-jwt", JwtService.TokenType.ACCESS).isEmpty());
    }

    @Test
    void expiryIsPresentInClaims() {
        JwtService service = service("secret-A");
        var claims = service.verify(
                service.issue("subject-hash", "admin", JwtService.TokenType.ACCESS),
                JwtService.TokenType.ACCESS);
        assertTrue(claims.isPresent());
        assertTrue(claims.get().expiresAt() > Instant.now().getEpochSecond());
        assertEquals("admin", claims.get().role());
        assertEquals("subject-hash", claims.get().subjectHash());
    }
}
