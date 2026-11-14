package top.chenyouyi.album.mgmt.provider.aws;

import top.chenyouyi.album.mgmt.common.ProviderResult;
import top.chenyouyi.album.mgmt.provider.port.AlertProvider;
import top.chenyouyi.album.mgmt.provider.port.LogProvider;
import top.chenyouyi.album.mgmt.provider.port.MetricProvider;
import top.chenyouyi.album.mgmt.provider.port.TraceProvider;

/**
 * 真实日志与指标读取。traces / alerts 目前没有接真实数据源，也不存在对应
 * HTTP 出口；明确返回 unavailable，绝不伪造。
 *
 * Real logs and metrics reads. Traces and alerts have no real data source yet and
 * no HTTP endpoint; they explicitly report unavailable instead of faking data.
 */
public class AwsObservabilityReadAdapter implements LogProvider, MetricProvider, TraceProvider, AlertProvider {

    private final AwsReadGateway gateway;
    private final MgmtAwsProperties props;

    public AwsObservabilityReadAdapter(AwsReadGateway gateway, MgmtAwsProperties props) {
        this.gateway = gateway;
        this.props = props;
    }

    @Override
    public ProviderResult events() {
        return AwsReadAdapterSupport.read("logs", gateway::logs, props);
    }

    @Override
    public ProviderResult series() {
        return AwsReadAdapterSupport.read("metrics", gateway::metrics, props);
    }

    @Override
    public ProviderResult traces() {
        return ProviderResult.unavailable("no trace backend connected (read-only surface)");
    }

    @Override
    public ProviderResult alerts() {
        return ProviderResult.unavailable("no alert backend connected (read-only surface)");
    }
}
