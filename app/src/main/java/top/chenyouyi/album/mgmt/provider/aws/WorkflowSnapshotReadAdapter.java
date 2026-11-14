package top.chenyouyi.album.mgmt.provider.aws;

import top.chenyouyi.album.mgmt.common.ProviderResult;
import top.chenyouyi.album.mgmt.provider.port.CicdProvider;

/**
 * CI/CD 域的真实数据源：CI 写入 S3 的只读 workflow 快照。
 * 管理站因此完全不直连 GitHub，也不需要任何长期 GitHub 凭据。
 *
 * Real CI/CD data source: the read-only workflow snapshot written to S3 by CI.
 * The management site never talks to GitHub and needs no long-lived GitHub token.
 */
public class WorkflowSnapshotReadAdapter implements CicdProvider {

    private final AwsReadGateway gateway;
    private final MgmtAwsProperties props;

    public WorkflowSnapshotReadAdapter(AwsReadGateway gateway, MgmtAwsProperties props) {
        this.gateway = gateway;
        this.props = props;
    }

    @Override
    public ProviderResult workflows() {
        return AwsReadAdapterSupport.read("cicd", gateway::workflows, props);
    }
}
