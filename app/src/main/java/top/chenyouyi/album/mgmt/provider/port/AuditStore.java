package top.chenyouyi.album.mgmt.provider.port;

import java.util.Map;

import top.chenyouyi.album.mgmt.common.ProviderResult;

/**
 * 审计存储 port：记录单条事件（已过滤）与只读查询。
 * 原型阶段使用进程内内存实现，不引入数据库。
 *
 * Audit store port: record a single (already-redacted) event and query read-only.
 * The prototype uses an in-memory implementation; no database is introduced.
 */
public interface AuditStore {

    /** 事件字段 who(哈希)/when/domain/action/correlationId/outcome，均已递归过滤。 */
    void record(Map<String, Object> event);

    ProviderResult query();
}
