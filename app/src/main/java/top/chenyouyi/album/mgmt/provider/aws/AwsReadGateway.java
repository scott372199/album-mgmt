package top.chenyouyi.album.mgmt.provider.aws;

import java.util.List;
import java.util.Map;

/**
 * cloud profile 的真实只读数据源网关。所有方法只做 list/describe/get，
 * 不写任何云资源；返回 provider-neutral Map，绝不把 vendor DTO 或完整 ARN
 * 带过这一层。实现可以连真实 AWS，也可以被测试替身替换。
 *
 * Real read-only data-source gateway for the cloud profile. Every method performs
 * list/describe/get only and never writes to the cloud. It returns provider-neutral
 * maps, so no vendor DTO or full ARN crosses this boundary. Implementations may
 * call real AWS or be replaced by a test double.
 */
public interface AwsReadGateway {

    List<Map<String, Object>> resources();

    List<Map<String, Object>> logs();

    List<Map<String, Object>> metrics();

    List<Map<String, Object>> billing();

    /** CI 写入的只读 workflow 快照 / CI-written read-only workflow snapshot. */
    List<Map<String, Object>> workflows();

    /** 由 IaC 边界推导的控制项声明 / Control declarations derived from the IaC boundary. */
    List<Map<String, Object>> securityControls();
}
