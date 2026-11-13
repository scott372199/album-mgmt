package top.chenyouyi.album.mgmt.provider.dto;

/**
 * 认证成功后的身份：subjectHash 为哈希派生主体（绝不存明文手机号），role 用于权限映射。
 * Granted identity: subjectHash is a hash-derived subject (never a plaintext phone),
 * and role drives permission mapping.
 */
public record GrantedIdentity(String subjectHash, String role) {
}
