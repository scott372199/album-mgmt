package top.chenyouyi.album.mgmt.provider.aws;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudfront.CloudFrontClient;
import software.amazon.awssdk.services.ecr.EcrClient;
import software.amazon.awssdk.services.ecs.EcsClient;
import top.chenyouyi.album.mgmt.provider.dto.ResourceSummary;

@Component
public class AwsInventoryReadAdapter {
    private final EcsClient ecs;
    private final EcrClient ecr;
    private final CloudFrontClient cloudFront;

    public AwsInventoryReadAdapter(@Value("${AWS_REGION:ap-southeast-1}") String region) {
        Region awsRegion = Region.of(region);
        this.ecs = EcsClient.builder().region(awsRegion).build();
        this.ecr = EcrClient.builder().region(awsRegion).build();
        this.cloudFront = CloudFrontClient.builder().region(Region.US_EAST_1).build();
    }

    public List<ResourceSummary> resources() {
        List<ResourceSummary> resources = new ArrayList<>();
        ecs.listClusters().clusterArns().forEach(arn ->
                resources.add(new ResourceSummary("ECS", resourceName(arn), "discovered", "aws")));
        ecr.describeRepositories().repositories().forEach(repository ->
                resources.add(new ResourceSummary("ECR", repository.repositoryName(), "discovered", "aws")));
        cloudFront.listDistributions().distributionList().items().forEach(distribution ->
                resources.add(new ResourceSummary("CloudFront", distribution.id(), distribution.status(), "aws")));
        return resources;
    }

    private static String resourceName(String arn) {
        int separator = arn.lastIndexOf('/');
        return separator >= 0 ? arn.substring(separator + 1) : arn;
    }
}
