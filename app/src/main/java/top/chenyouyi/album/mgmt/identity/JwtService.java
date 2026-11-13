package top.chenyouyi.album.mgmt.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import top.chenyouyi.album.mgmt.common.Crypto;

/**
 * 手写 HS256 JWT 签发/校验（零外部 JWT 依赖，便于离线构建）。
 * subject 为哈希派生值（不存明文手机号），token 区分 access/refresh 并校验过期与签名。
 * 密钥仅来自 JwtProperties(MGMT_JWT_SECRET)，与上传侧完全独立。
 *
 * Hand-rolled HS256 JWT issue/verify (no external JWT dependency, offline-friendly).
 * The subject is hash-derived (no plaintext phone); tokens are typed access/refresh and
 * validated for expiration and signature. The key comes only from MGMT_JWT_SECRET.
 */
@Service
public class JwtService {

    public enum TokenType {
        ACCESS("access"),
        REFRESH("refresh");

        private final String value;

        TokenType(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }

    /** 校验通过后的最小声明 / Minimal verified claims. */
    public record Claims(String subjectHash, String role, long expiresAt) {
    }

    private static final String HEADER = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";

    private final JwtProperties properties;
    private final ObjectMapper objectMapper;

    public JwtService(JwtProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public String issue(String subjectHash, String role, TokenType type) {
        long now = Instant.now().getEpochSecond();
        long ttl = type == TokenType.ACCESS
                ? properties.accessTtlSeconds()
                : properties.refreshTtlSeconds();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sub", subjectHash);
        payload.put("role", role);
        payload.put("typ", type.value());
        payload.put("iat", now);
        payload.put("exp", now + ttl);
        payload.put("jti", UUID.randomUUID().toString());

        String encodedHeader = Crypto.b64UrlEncode(HEADER.getBytes(StandardCharsets.UTF_8));
        String encodedPayload;
        try {
            encodedPayload = Crypto.b64UrlEncode(objectMapper.writeValueAsBytes(payload));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to encode JWT payload", e);
        }
        String signingInput = encodedHeader + "." + encodedPayload;
        byte[] signature = Crypto.hmacSha256(
                properties.secret(), signingInput.getBytes(StandardCharsets.US_ASCII));
        return signingInput + "." + Crypto.b64UrlEncode(signature);
    }

    /** 校验签名、alg、类型与过期；任一失败返回 empty / Verify signature, alg, type and expiry. */
    public Optional<Claims> verify(String token, TokenType expected) {
        try {
            if (token == null || token.isBlank()) {
                return Optional.empty();
            }
            String[] parts = token.split("\\.", -1);
            if (parts.length != 3) {
                return Optional.empty();
            }
            JsonNode header = objectMapper.readTree(Crypto.b64UrlDecode(parts[0]));
            if (!"HS256".equals(header.path("alg").asText())) {
                return Optional.empty(); // 拒绝 alg:none / reject alg:none
            }
            byte[] expectedSignature = Crypto.hmacSha256(
                    properties.secret(), (parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            byte[] actualSignature = Crypto.b64UrlDecode(parts[2]);
            if (!MessageDigest.isEqual(expectedSignature, actualSignature)) {
                return Optional.empty();
            }
            JsonNode payload = objectMapper.readTree(Crypto.b64UrlDecode(parts[1]));
            long expiresAt = payload.path("exp").asLong(0L);
            if (expiresAt <= Instant.now().getEpochSecond()) {
                return Optional.empty(); // 过期 / expired
            }
            if (!expected.value().equals(payload.path("typ").asText())) {
                return Optional.empty();
            }
            String subjectHash = payload.path("sub").asText("");
            String role = payload.path("role").asText("");
            if (subjectHash.isEmpty() || role.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new Claims(subjectHash, role, expiresAt));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
