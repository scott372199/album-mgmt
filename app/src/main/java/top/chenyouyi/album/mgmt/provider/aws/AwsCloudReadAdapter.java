package top.chenyouyi.album.mgmt.provider.aws;

import top.chenyouyi.album.mgmt.common.ProviderResult;
import top.chenyouyi.album.mgmt.provider.port.CloudProvider;

/**
 * 真实资源清单（blocker #6）。状态只来自本次只读查询的结果：
 * active / degraded / unknown 三选一，绝不把 ACTIVE 资源报成 planned。
 *
 * Real resource inventory (blocker #6). Status is derived only from this read:
 * active, degraded or unknown; an ACTIVE resource is never reported as planned.
 */
public class AwsCloudReadAdapter implements CloudProvider {

    private final AwsReadGateway gateway;
    private final MgmtAwsProperties props;

    public AwsCloudReadAdapter(AwsReadGateway gateway, MgmtAwsProperties props) {
        this.gateway = gateway;
        this.props = props;
    }

    @Override
    public ProviderResult resources() {
        return AwsReadAdapterSupport.read("resources", gateway::resources, props);
    }
}
