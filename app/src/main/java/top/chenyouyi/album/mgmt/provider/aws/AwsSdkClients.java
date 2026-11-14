package top.chenyouyi.album.mgmt.provider.aws;

import software.amazon.awssdk.services.cloudfront.CloudFrontClient;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.costexplorer.CostExplorerClient;
import software.amazon.awssdk.services.ecr.EcrClient;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * AWS SDK client 注入句柄。本地/offline profile 用 empty()，绝不构造真实 client、
 * 不连真实云。cloud profile 下由 IaC 的只读 task role 提供凭据，容器里没有长期密钥。
 *
 * AWS SDK client injection handle. Local/offline profiles use empty() and never
 * build a real client. In the cloud profile the read-only task role supplies
 * credentials; the container holds no long-lived key.
 */
public record AwsSdkClients(
        EcsClient ecs,
        EcrClient ecr,
        CloudFrontClient cloudFront,
        CloudWatchLogsClient cloudWatchLogs,
        CloudWatchClient cloudWatch,
        CostExplorerClient costExplorer,
        S3Client s3) {

    /** 本地占位：全部为 null / Local placeholder: every field is null. */
    public static AwsSdkClients empty() {
        return new AwsSdkClients(null, null, null, null, null, null, null);
    }

    public boolean available() {
        return ecs != null || ecr != null || cloudFront != null
                || cloudWatchLogs != null || cloudWatch != null
                || costExplorer != null || s3 != null;
    }
}
