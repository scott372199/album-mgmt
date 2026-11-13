package top.chenyouyi.album.mgmt.provider.fixture;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.stereotype.Component;

import top.chenyouyi.album.mgmt.common.CorrelationId;
import top.chenyouyi.album.mgmt.common.Crypto;
import top.chenyouyi.album.mgmt.common.ProviderResult;
import top.chenyouyi.album.mgmt.provider.port.AuditStore;

/**
 * 进程内审计存储（原型阶段不引入数据库）。
 * 真实请求事件由 CorrelationAuditFilter 追加；另含少量明确标注的 fixture 种子事件。
 * 所有事件在写入前已完成 forbiddenFields 递归过滤。
 *
 * In-memory audit store (no database in the prototype). Real request events are appended
 * by CorrelationAuditFilter; a few clearly-labelled fixture seed events are included.
 * Every event is recursively redacted before being recorded.
 */
@Component
public class FixtureAuditStore implements AuditStore {

    private static final int MAX_EVENTS = 200;

    private final CopyOnWriteArrayList<Map<String, Object>> events = new CopyOnWriteArrayList<>();

    public FixtureAuditStore() {
        // 演示用种子事件（source=fixture）/ Seed events for the demo (source=fixture)
        events.add(seed("security", "allow"));
        events.add(seed("resources", "allow"));
    }

    private static Map<String, Object> seed(String domain, String outcome) {
        return FixtureData.obj(
                "who", Crypto.sha256Hex("fixture-system"),
                "when", Instant.now().toString(),
                "domain", domain,
                "action", "read",
                "correlationId", CorrelationId.generate(),
                "outcome", outcome);
    }

    @Override
    public void record(Map<String, Object> event) {
        events.add(0, event); // newest first / 最新在前
        if (events.size() > MAX_EVENTS) {
            events.remove(events.size() - 1);
        }
    }

    @Override
    public ProviderResult query() {
        return ProviderResult.fixture(List.copyOf(events));
    }
}
