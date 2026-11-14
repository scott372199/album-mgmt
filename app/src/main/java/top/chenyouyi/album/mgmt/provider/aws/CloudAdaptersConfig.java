package top.chenyouyi.album.mgmt.provider.aws;

import java.time.Duration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudfront.CloudFrontClient;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.costexplorer.CostExplorerClient;
import software.amazon.awssdk.services.ecr.EcrClient;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.s3.S3Client;

import top.chenyouyi.album.mgmt.provider.port.AlertProvider;
import top.chenyouyi.album.mgmt.provider.port.BillingProvider;
import top.chenyouyi.album.mgmt.provider.port.CicdProvider;
import top.chenyouyi.album.mgmt.provider.port.CloudProvider;
import top.chenyouyi.album.mgmt.provider.port.LogProvider;
import top.chenyouyi.album.mgmt.provider.port.MetricProvider;
import top.chenyouyi.album.mgmt.provider.port.SecurityProvider;
import top.chenyouyi.album.mgmt.provider.port.TraceProvider;

/**
 * cloud profile 的真实只读绑定（SPRING_PROFILES_ACTIVE=cloud）。
 * SDK client 使用默认凭据链，凭据由 ECS 只读 task role 提供；容器里没有长期密钥。
 * 每次调用都有超时上限，失败由 adapter 明确降级为 unavailable，绝不伪造数据。
 *
 * Real read-only bindings for the cloud profile. SDK clients use the default
 * credentials chain backed by the ECS read-only task role, so no long-lived key
 * lives in the container. Every call has a timeout; failures degrade honestly to
 * unavailable and never produce fake data.
 *
 * 身份与审计仍用进程内实现：它们是本服务自己的状态，不是外部运维数据源。
 * Identity and audit stay in-process: they are this service's own state, not an
 * external operational data source.
 */
@Configuration
@Profile("cloud")
@EnableConfigurationProperties(MgmtAwsProperties.class)
public class CloudAdaptersConfig {

    @Bean
    AwsSdkClients awsSdkClients(MgmtAwsProperties props) {
        Region region = Region.of(props.getRegion());
        Region global = Region.of(props.getGlobalRegion());
        ClientOverrideConfiguration override = ClientOverrideConfiguration.builder()
                .apiCallTimeout(Duration.ofMillis(props.boundedTimeoutMillis()))
                .apiCallAttemptTimeout(Duration.ofMillis(props.boundedTimeoutMillis()))
                .build();
        return new AwsSdkClients(
                EcsClient.builder().region(region).overrideConfiguration(override).build(),
                EcrClient.builder().region(region).overrideConfiguration(override).build(),
                CloudFrontClient.builder().region(global).overrideConfiguration(override).build(),
                CloudWatchLogsClient.builder().region(region).overrideConfiguration(override).build(),
                CloudWatchClient.builder().region(region).overrideConfiguration(override).build(),
                CostExplorerClient.builder().region(global).overrideConfiguration(override).build(),
                S3Client.builder().region(region).overrideConfiguration(override).build());
    }

    @Bean
    AwsReadGateway awsReadGateway(AwsSdkClients clients, MgmtAwsProperties props) {
        return new SdkAwsReadGateway(clients, props);
    }

    @Bean
    CloudProvider cloudProvider(AwsReadGateway gateway, MgmtAwsProperties props) {
        return new AwsCloudReadAdapter(gateway, props);
    }

    @Bean
    CicdProvider cicdProvider(AwsReadGateway gateway, MgmtAwsProperties props) {
        return new WorkflowSnapshotReadAdapter(gateway, props);
    }

    @Bean
    LogProvider logProvider(AwsReadGateway gateway, MgmtAwsProperties props) {
        return new AwsObservabilityReadAdapter(gateway, props);
    }

    @Bean
    MetricProvider metricProvider(AwsReadGateway gateway, MgmtAwsProperties props) {
        return new AwsObservabilityReadAdapter(gateway, props);
    }

    @Bean
    TraceProvider traceProvider(AwsReadGateway gateway, MgmtAwsProperties props) {
        return new AwsObservabilityReadAdapter(gateway, props);
    }

    @Bean
    AlertProvider alertProvider(AwsReadGateway gateway, MgmtAwsProperties props) {
        return new AwsObservabilityReadAdapter(gateway, props);
    }

    @Bean
    BillingProvider billingProvider(AwsReadGateway gateway, MgmtAwsProperties props) {
        return new AwsBillingReadAdapter(gateway, props);
    }

    @Bean
    SecurityProvider securityProvider(AwsReadGateway gateway, MgmtAwsProperties props) {
        return new AwsSecurityReadAdapter(gateway, props);
    }
}
