package top.chenyouyi.album.mgmt.provider.port;

import java.util.Optional;

import top.chenyouyi.album.mgmt.provider.dto.GrantedIdentity;

/**
 * 身份校验 port：白名单 + scrypt 共享口令校验。
 * 生产实现读取 SSM /album/auth/phones（与上传侧共享）与 /album/auth/key_hash；
 * 本地 fixture 实现用演示标识 + scrypt，绝不连 SSM。
 *
 * Identity verification port: allowlist + scrypt shared-key verification.
 * Production reads SSM /album/auth/phones (shared with upload) and /album/auth/key_hash;
 * the local fixture uses demo identifiers + scrypt and never calls SSM.
 */
@FunctionalInterface
public interface IdentityProvider {

    /**
     * @param principal 手机号（生产）/ 演示标识（本地 fixture）/ phone (prod) or demo id (fixture)
     * @param sharedKey 共享口令 / shared key
     */
    Optional<GrantedIdentity> authenticate(String principal, String sharedKey);
}
