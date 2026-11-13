package top.chenyouyi.album.mgmt.identity;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 管理站 JWT 配置：密钥来自独立 MGMT_JWT_SECRET，绝不复用上传侧 secret。
 * 未配置时进程内生成随机密钥（每次启动独立，不落仓库、不写日志），保证本地零云依赖可启动。
 * access 1h / refresh 7d（契约 identity.accessTokenTtl/refreshTokenTtl）。
 *
 * Management JWT config: the key comes from the independent MGMT_JWT_SECRET, never the
 * upload-side secret. When unset, a random in-process key is generated (never persisted or
 * logged) so the service starts with zero cloud dependency. Access 1h / refresh 7d.
 */
@Component
public class JwtProperties {

    private final byte[] secret;
    private final long accessTtlSeconds;
    private final long refreshTtlSeconds;

    public JwtProperties(
            @Value("${mgmt.jwt.secret:}") String secret,
            @Value("${mgmt.jwt.access-ttl-seconds:3600}") long accessTtlSeconds,
            @Value("${mgmt.jwt.refresh-ttl-seconds:604800}") long refreshTtlSeconds) {

        if (secret == null || secret.isBlank()) {
            byte[] generated = new byte[32];
            new SecureRandom().nextBytes(generated);
            this.secret = generated;
        } else {
            this.secret = secret.getBytes(StandardCharsets.UTF_8);
        }
        this.accessTtlSeconds = accessTtlSeconds;
        this.refreshTtlSeconds = refreshTtlSeconds;
    }

    /** 测试可用不同 secret 构造，验证 JWT 独立性 / Tests can pass another secret to prove isolation. */
    public byte[] secret() {
        return secret.clone();
    }

    public long accessTtlSeconds() {
        return accessTtlSeconds;
    }

    public long refreshTtlSeconds() {
        return refreshTtlSeconds;
    }
}
