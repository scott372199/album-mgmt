package top.chenyouyi.album.mgmt.provider.port;

import top.chenyouyi.album.mgmt.common.ProviderResult;

/** 告警状态聚合 port / Alert-status aggregation port. */
@FunctionalInterface
public interface AlertProvider {
    ProviderResult alerts();
}
