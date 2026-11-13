package top.chenyouyi.album.mgmt.provider.port;

import top.chenyouyi.album.mgmt.common.ProviderResult;

/** 只读账单摘要 port / Read-only billing summary port. */
@FunctionalInterface
public interface BillingProvider {
    ProviderResult records();
}
