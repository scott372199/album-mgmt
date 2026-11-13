package top.chenyouyi.album.mgmt.provider.port;

import top.chenyouyi.album.mgmt.common.ProviderResult;

/** 结构化日志 port / Structured log port. 不可用时返回 source=unavailable。 */
@FunctionalInterface
public interface LogProvider {
    ProviderResult events();
}
