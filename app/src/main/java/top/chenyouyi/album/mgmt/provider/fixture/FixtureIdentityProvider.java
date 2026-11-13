package top.chenyouyi.album.mgmt.provider.fixture;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import top.chenyouyi.album.mgmt.common.Crypto;
import top.chenyouyi.album.mgmt.common.ScryptUtil;
import top.chenyouyi.album.mgmt.provider.dto.GrantedIdentity;
import top.chenyouyi.album.mgmt.provider.port.IdentityProvider;

/**
 * 本地 fixture IdentityProvider：白名单 + scrypt 共享口令校验，绝不连 SSM。
 * 白名单在配置为「演示标识 -> 角色」映射（非手机号、非真实凭据）；
 * 生产实现会读取 SSM /album/auth/phones 手机号白名单与 /album/auth/key_hash。
 * subject 用 pepper + principal 的 SHA-256 派生，不存明文手机号。
 *
 * Local fixture IdentityProvider: allowlist + scrypt shared-key verification, no SSM.
 * The allowlist maps demo identifiers (not phone numbers, not real credentials) to roles.
 * Production reads the SSM phone allowlist and shared key. The subject is a SHA-256
 * derivation of pepper + principal; no plaintext phone is stored.
 */
@Component
public class FixtureIdentityProvider implements IdentityProvider {

    private final Map<String, String> roleByPrincipal;
    private final byte[] salt;
    private final byte[] expectedSharedKeyHash;
    private final String pepper;

    public FixtureIdentityProvider(
            @Value("${mgmt.identity.accounts}") String accounts,
            @Value("${mgmt.identity.demo-shared-key}") String demoSharedKey,
            @Value("${mgmt.identity.scrypt-salt}") String scryptSalt,
            @Value("${mgmt.identity.pepper}") String pepper) {

        Map<String, String> roles = new HashMap<>();
        for (String entry : accounts.split(",")) {
            String[] parts = entry.trim().split("\\|");
            if (parts.length == 2) {
                roles.put(parts[0].trim().toLowerCase(Locale.ROOT), parts[1].trim());
            }
        }
        this.roleByPrincipal = Map.copyOf(roles);
        this.salt = scryptSalt.getBytes(StandardCharsets.UTF_8);
        this.expectedSharedKeyHash = ScryptUtil.derive(demoSharedKey, this.salt);
        this.pepper = pepper;
    }

    @Override
    public Optional<GrantedIdentity> authenticate(String principal, String sharedKey) {
        if (principal == null || sharedKey == null) {
            return Optional.empty();
        }
        String normalised = principal.trim().toLowerCase(Locale.ROOT);
        String role = roleByPrincipal.get(normalised);       // 白名单匹配 / allowlist match
        if (role == null) {
            return Optional.empty();
        }
        if (!ScryptUtil.matches(sharedKey, salt, expectedSharedKeyHash)) { // scrypt 校验 / scrypt verify
            return Optional.empty();
        }
        String subjectHash = Crypto.sha256Hex(pepper + ":" + normalised);  // 哈希派生 / hash-derived
        return Optional.of(new GrantedIdentity(subjectHash, role));
    }
}
