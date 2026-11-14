package top.chenyouyi.album.mgmt.provider.aws;

import top.chenyouyi.album.mgmt.common.ProviderResult;
import top.chenyouyi.album.mgmt.provider.port.BillingProvider;

/**
 * 真实账单读取：Cost Explorer 当月至今的 UnblendedCost，如实保留币种。
 *
 * Real billing read: month-to-date UnblendedCost from Cost Explorer, keeping the
 * currency exactly as reported rather than pretending it is a different one.
 */
public class AwsBillingReadAdapter implements BillingProvider {

    private final AwsReadGateway gateway;
    private final MgmtAwsProperties props;

    public AwsBillingReadAdapter(AwsReadGateway gateway, MgmtAwsProperties props) {
        this.gateway = gateway;
        this.props = props;
    }

    @Override
    public ProviderResult records() {
        return AwsReadAdapterSupport.read("billing", gateway::billing, props);
    }
}
