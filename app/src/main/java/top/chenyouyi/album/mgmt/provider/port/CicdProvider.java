package top.chenyouyi.album.mgmt.provider.port;

import top.chenyouyi.album.mgmt.common.ProviderResult;

/** CI/CD 工作流只读 port / Read-only CI/CD workflow port. */
@FunctionalInterface
public interface CicdProvider {
    ProviderResult workflows();
}
