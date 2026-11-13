package top.chenyouyi.album.mgmt.provider.aws;

import top.chenyouyi.album.mgmt.common.ProviderResult;
import top.chenyouyi.album.mgmt.provider.port.BillingProvider;

/** AWS 账单只读骨架 / AWS billing read-only skeleton (Cost Explorer). */
public class AwsBillingReadAdapter implements BillingProvider {

    private final AwsSdkClients clients;

    public AwsBillingReadAdapter(AwsSdkClients clients) {
        this.clients = clients;
    }

    @Override
    public ProviderResult records() {
        if (!clients.available()) {
            return ProviderResult.unavailable("AWS read SDK client not injected (local/offline profile)");
        }
        // 只读骨架：生产调用 Cost Explorer GetCostAndUsage，映射为 provider-neutral billing 记录。
        // Read-only skeleton: production maps GetCostAndUsage into provider-neutral billing records.
        return ProviderResult.unavailable("AwsBillingReadAdapter is a read-only skeleton; live SDK mapping disabled");
    }
}
