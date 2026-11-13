package top.chenyouyi.album.mgmt.provider.port;

import top.chenyouyi.album.mgmt.common.ProviderResult;

/** 安全姿态 port / Security posture port. 仅控制项状态，不含 secret 值或 IAM 写操作。 */
@FunctionalInterface
public interface SecurityProvider {
    ProviderResult controls();
}
