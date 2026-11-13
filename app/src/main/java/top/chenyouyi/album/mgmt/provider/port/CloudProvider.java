package top.chenyouyi.album.mgmt.provider.port;

import top.chenyouyi.album.mgmt.common.ProviderResult;

/**
 * 资源目录 port（provider-neutral）/ Resource inventory port (provider-neutral).
 * 公共契约只暴露 provider/account/region/type/id/status/tags/extensions，绝不返回 vendor DTO 或完整 ARN。
 */
@FunctionalInterface
public interface CloudProvider {
    ProviderResult resources();
}
