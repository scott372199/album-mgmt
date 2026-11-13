package top.chenyouyi.album.mgmt.provider.aws;

import top.chenyouyi.album.mgmt.common.ProviderResult;
import top.chenyouyi.album.mgmt.provider.port.SecurityProvider;

/**
 * AWS 安全姿态只读骨架（仅控制项状态，绝不返回 secret 值或执行 IAM 写操作）。
 * AWS security-posture read-only skeleton (control status only; never returns secret values
 * or performs IAM writes).
 */
public class AwsSecurityReadAdapter implements SecurityProvider {

    private final AwsSdkClients clients;

    public AwsSecurityReadAdapter(AwsSdkClients clients) {
        this.clients = clients;
    }

    @Override
    public ProviderResult controls() {
        if (!clients.available()) {
            return ProviderResult.unavailable("AWS read SDK client not injected (local/offline profile)");
        }
        return ProviderResult.unavailable("AwsSecurityReadAdapter is a read-only skeleton; live SDK mapping disabled");
    }
}
