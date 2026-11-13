package top.chenyouyi.album.mgmt.provider.port;

import top.chenyouyi.album.mgmt.common.ProviderResult;

/** 指标 port（provider-neutral）/ Metrics port (provider-neutral). */
@FunctionalInterface
public interface MetricProvider {
    ProviderResult series();
}
