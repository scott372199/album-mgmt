package top.chenyouyi.album.mgmt.provider.github;

import java.net.http.HttpClient;

import top.chenyouyi.album.mgmt.common.ProviderResult;
import top.chenyouyi.album.mgmt.provider.port.CicdProvider;

/**
 * GitHub Actions 只读骨架：可注入 HttpClient 与只读 token。
 * 本地/offline profile 下 client/token 为 null，绝不发起真实 API 调用、绝不伪造。
 *
 * GitHub Actions read-only skeleton: an HttpClient and read-only token are injectable.
 * In local/offline profiles both are null; no real API call is made and no data is faked.
 */
public class GitHubActionsReadAdapter implements CicdProvider {

    private final HttpClient httpClient;
    private final String token;

    public GitHubActionsReadAdapter(HttpClient httpClient, String token) {
        this.httpClient = httpClient;
        this.token = token;
    }

    @Override
    public ProviderResult workflows() {
        if (httpClient == null || token == null || token.isBlank()) {
            return ProviderResult.unavailable("GitHub Actions client/token not injected (local/offline profile)");
        }
        // 只读骨架：生产 GET /repos/{repo}/actions/runs，映射为 provider-neutral workflow DTO。
        // Read-only skeleton: production maps GET actions/runs into provider-neutral workflow DTOs.
        return ProviderResult.unavailable("GitHubActionsReadAdapter is a read-only skeleton; live API mapping disabled");
    }
}
