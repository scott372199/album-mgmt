package top.chenyouyi.album.mgmt.provider.aws;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import top.chenyouyi.album.mgmt.provider.github.GitHubActionsReadAdapter;
import top.chenyouyi.album.mgmt.provider.port.AlertProvider;
import top.chenyouyi.album.mgmt.provider.port.BillingProvider;
import top.chenyouyi.album.mgmt.provider.port.CicdProvider;
import top.chenyouyi.album.mgmt.provider.port.CloudProvider;
import top.chenyouyi.album.mgmt.provider.port.LogProvider;
import top.chenyouyi.album.mgmt.provider.port.MetricProvider;
import top.chenyouyi.album.mgmt.provider.port.SecurityProvider;
import top.chenyouyi.album.mgmt.provider.port.TraceProvider;

/**
 * cloud profile 下的 provider 绑定（仅在显式启用 SPRING_PROFILES_ACTIVE=cloud 时加载）。
 * AWS SDK client 注入点默认为 null：本地不构造真实 client、不连真实云；
 * 生产由 IaC 提供带只读 task role 的 AwsSdkClients @Bean 替换本默认 bean。
 *
 * Provider bindings for the cloud profile (loaded only with SPRING_PROFILES_ACTIVE=cloud).
 * The AWS SDK client injection point defaults to null: no real client, no cloud call locally.
 * Production replaces the default AwsSdkClients bean with one carrying a read-only task role.
 */
@Configuration
@Profile("cloud")
public class CloudAdaptersConfig {

    @Bean
    AwsSdkClients awsSdkClients() {
        return new AwsSdkClients(null, null, null); // 本地骨架不注入真实 client / no real client locally
    }

    @Bean
    CloudProvider cloudProvider(AwsSdkClients clients) {
        return new AwsCloudReadAdapter(clients);
    }

    @Bean
    CicdProvider cicdProvider() {
        return new GitHubActionsReadAdapter(null, null);
    }

    @Bean
    LogProvider logProvider(AwsSdkClients clients) {
        return new AwsObservabilityReadAdapter(clients);
    }

    @Bean
    MetricProvider metricProvider(AwsSdkClients clients) {
        return new AwsObservabilityReadAdapter(clients);
    }

    @Bean
    TraceProvider traceProvider(AwsSdkClients clients) {
        return new AwsObservabilityReadAdapter(clients);
    }

    @Bean
    AlertProvider alertProvider(AwsSdkClients clients) {
        return new AwsObservabilityReadAdapter(clients);
    }

    @Bean
    BillingProvider billingProvider(AwsSdkClients clients) {
        return new AwsBillingReadAdapter(clients);
    }

    @Bean
    SecurityProvider securityProvider(AwsSdkClients clients) {
        return new AwsSecurityReadAdapter(clients);
    }
}
