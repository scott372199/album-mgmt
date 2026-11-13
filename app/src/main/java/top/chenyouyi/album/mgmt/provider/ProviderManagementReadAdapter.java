package top.chenyouyi.album.mgmt.provider;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import top.chenyouyi.album.mgmt.provider.aws.AwsInventoryReadAdapter;
import top.chenyouyi.album.mgmt.provider.dto.ManagementSnapshot;
import top.chenyouyi.album.mgmt.provider.github.GitHubActionsReadAdapter;

@Component
@Profile("provider")
public class ProviderManagementReadAdapter implements ManagementReadAdapter {
    private final AwsInventoryReadAdapter aws;
    private final GitHubActionsReadAdapter github;

    public ProviderManagementReadAdapter(AwsInventoryReadAdapter aws, GitHubActionsReadAdapter github) {
        this.aws = aws;
        this.github = github;
    }

    @Override
    public ManagementSnapshot snapshot() {
        return new ManagementSnapshot(aws.resources(), github.workflows());
    }
}
