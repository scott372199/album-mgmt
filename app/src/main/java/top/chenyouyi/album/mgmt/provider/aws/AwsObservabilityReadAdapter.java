package top.chenyouyi.album.mgmt.provider.aws;

import top.chenyouyi.album.mgmt.common.ProviderResult;
import top.chenyouyi.album.mgmt.provider.port.AlertProvider;
import top.chenyouyi.album.mgmt.provider.port.LogProvider;
import top.chenyouyi.album.mgmt.provider.port.MetricProvider;
import top.chenyouyi.album.mgmt.provider.port.TraceProvider;

/**
 * AWS 日志/指标/链路/告警只读骨架（LogProvider/MetricProvider/TraceProvider/AlertProvider）。
 * 仅占位：不可用时明确返回 source=unavailable，绝不伪造。
 *
 * AWS logs/metrics/traces/alerts read-only skeleton. Placeholder only: returns
 * source=unavailable with a reason and never fakes data.
 */
public class AwsObservabilityReadAdapter implements LogProvider, MetricProvider, TraceProvider, AlertProvider {

    private final AwsSdkClients clients;

    public AwsObservabilityReadAdapter(AwsSdkClients clients) {
        this.clients = clients;
    }

    @Override
    public ProviderResult events() {
        return skeleton("logs");
    }

    @Override
    public ProviderResult series() {
        return skeleton("metrics");
    }

    @Override
    public ProviderResult traces() {
        return skeleton("traces");
    }

    @Override
    public ProviderResult alerts() {
        return skeleton("alerts");
    }

    private ProviderResult skeleton(String domain) {
        if (!clients.available()) {
            return ProviderResult.unavailable("AWS read SDK client not injected (local/offline profile)");
        }
        return ProviderResult.unavailable(
                "Aws*ReadAdapter " + domain + " is a read-only skeleton; live SDK mapping disabled");
    }
}
