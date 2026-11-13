package top.chenyouyi.album.mgmt.provider.aws;

import software.amazon.awssdk.services.cloudfront.CloudFrontClient;
import software.amazon.awssdk.services.ecr.EcrClient;
import software.amazon.awssdk.services.ecs.EcsClient;

/**
 * AWS SDK client 注入句柄。本地/offline profile 下三个字段均为 null，绝不构造真实 client、不连真实云。
 * 生产由 IaC 注入只带只读权限的 task role 与具体 client（iamBoundaries.album-mgmt-container）。
 *
 * AWS SDK client injection handle. In local/offline profiles all fields are null; no real
 * client is built and no cloud call is made. Production injects read-only task-role clients.
 */
public record AwsSdkClients(EcsClient ecs, EcrClient ecr, CloudFrontClient cloudFront) {

    public boolean available() {
        return ecs != null || ecr != null || cloudFront != null;
    }
}
