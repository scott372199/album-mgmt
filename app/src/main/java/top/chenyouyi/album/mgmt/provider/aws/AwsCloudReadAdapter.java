package top.chenyouyi.album.mgmt.provider.aws;

import top.chenyouyi.album.mgmt.common.ProviderResult;
import top.chenyouyi.album.mgmt.provider.port.CloudProvider;

/**
 * AWS 资源目录只读骨架 / AWS resource-inventory read-only skeleton.
 * 可注入 SDK client；骨架阶段不发起真实调用，绝不伪造数据。
 */
public class AwsCloudReadAdapter implements CloudProvider {

    private final AwsSdkClients clients;

    public AwsCloudReadAdapter(AwsSdkClients clients) {
        this.clients = clients;
    }

    @Override
    public ProviderResult resources() {
        if (!clients.available()) {
            return ProviderResult.unavailable("AWS read SDK client not injected (local/offline profile)");
        }
        // 只读映射骨架 / Read-only mapping skeleton:
        // 生产调用 ecs.listClusters / ecr.describeRepositories / cloudFront.listDistributions，
        // 映射为契约 resourceDto，剥离完整 ARN、SSM 值与内部对象 key，绝不返回 vendor DTO。
        // Production maps read-only SDK calls into resourceDto, stripping full ARNs,
        // SSM values and internal object keys; no vendor DTO ever crosses the port.
        return ProviderResult.unavailable("AwsCloudReadAdapter is a read-only skeleton; live SDK mapping disabled");
    }
}
