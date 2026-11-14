package top.chenyouyi.album.mgmt.provider.aws;

import top.chenyouyi.album.mgmt.common.ProviderResult;
import top.chenyouyi.album.mgmt.provider.port.SecurityProvider;

/**
 * 安全控制姿态。这些是由 IaC 边界与代码事实推导的声明，不是采集到的云指标；
 * evidence 逐条写明依据，绝不声称来自实时探测。
 *
 * Security control posture. These are declarations derived from the IaC boundary
 * and code facts, not collected cloud metrics; each evidence string names its
 * basis and never claims a live probe.
 */
public class AwsSecurityReadAdapter implements SecurityProvider {

    private final AwsReadGateway gateway;
    private final MgmtAwsProperties props;

    public AwsSecurityReadAdapter(AwsReadGateway gateway, MgmtAwsProperties props) {
        this.gateway = gateway;
        this.props = props;
    }

    @Override
    public ProviderResult controls() {
        return AwsReadAdapterSupport.read("security", gateway::securityControls, props);
    }
}
