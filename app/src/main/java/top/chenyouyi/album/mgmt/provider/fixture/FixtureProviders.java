package top.chenyouyi.album.mgmt.provider.fixture;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import top.chenyouyi.album.mgmt.common.ProviderResult;
import top.chenyouyi.album.mgmt.provider.port.AlertProvider;
import top.chenyouyi.album.mgmt.provider.port.BillingProvider;
import top.chenyouyi.album.mgmt.provider.port.CicdProvider;
import top.chenyouyi.album.mgmt.provider.port.CloudProvider;
import top.chenyouyi.album.mgmt.provider.port.LogProvider;
import top.chenyouyi.album.mgmt.provider.port.MetricProvider;
import top.chenyouyi.album.mgmt.provider.port.SecurityProvider;
import top.chenyouyi.album.mgmt.provider.port.TraceProvider;

/**
 * 默认（非 cloud）profile 的 provider 绑定：本地 fixture 数据 + 明确的 unavailable。
 * 不可用域绝不伪造数据，统一返回 source=unavailable 与原因。
 *
 * Default (non-cloud) profile provider bindings: local fixtures plus explicit unavailability.
 * Unavailable domains never fake data; they return source=unavailable with a reason.
 */
@Configuration
@Profile("!cloud")
public class FixtureProviders {

    @Bean
    CloudProvider cloudProvider() {
        return () -> ProviderResult.fixture(FixtureData.resources());
    }

    @Bean
    CicdProvider cicdProvider() {
        return () -> ProviderResult.fixture(FixtureData.workflows());
    }

    @Bean
    MetricProvider metricProvider() {
        return () -> ProviderResult.fixture(FixtureData.metricCatalog());
    }

    @Bean
    SecurityProvider securityProvider() {
        return () -> ProviderResult.fixture(FixtureData.securityControls());
    }

    @Bean
    LogProvider logProvider() {
        return () -> ProviderResult.unavailable("Loki/Grafana log adapter not configured (local fixture profile)");
    }

    @Bean
    TraceProvider traceProvider() {
        return () -> ProviderResult.unavailable("Tempo trace adapter not configured (local fixture profile)");
    }

    @Bean
    AlertProvider alertProvider() {
        return () -> ProviderResult.unavailable("Grafana alerting adapter not configured (local fixture profile)");
    }

    @Bean
    BillingProvider billingProvider() {
        return () -> ProviderResult.unavailable("Cost Explorer billing adapter not configured (local fixture profile)");
    }
}
