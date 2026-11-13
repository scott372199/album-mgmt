package top.chenyouyi.album.mgmt.provider.port;

import top.chenyouyi.album.mgmt.common.ProviderResult;

/** 链路/correlation 查询 port / Trace & correlation lookup port. */
@FunctionalInterface
public interface TraceProvider {
    ProviderResult traces();
}
