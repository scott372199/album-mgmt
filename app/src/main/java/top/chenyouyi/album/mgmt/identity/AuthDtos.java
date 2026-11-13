package top.chenyouyi.album.mgmt.identity;

/**
 * 认证请求 DTO。字段名使用契约语义；请求体绝不会写入日志或审计事件。
 * Auth request DTOs. Request bodies are never written to logs or audit events.
 */
public final class AuthDtos {

    private AuthDtos() {
    }

    public record LoginRequest(String principal, String password) {
    }

    public record RefreshRequest(String refreshToken) {
    }
}
