package top.chenyouyi.album.mgmt.provider.fixture;

import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import top.chenyouyi.album.mgmt.provider.ManagementReadAdapter;
import top.chenyouyi.album.mgmt.provider.dto.ManagementSnapshot;
import top.chenyouyi.album.mgmt.provider.dto.ResourceSummary;
import top.chenyouyi.album.mgmt.provider.dto.WorkflowSummary;

@Component
@Profile("!provider")
public class FixtureManagementReadAdapter implements ManagementReadAdapter {
    @Override
    public ManagementSnapshot snapshot() {
        return new ManagementSnapshot(
                List.of(
                        new ResourceSummary("ECS Express", "album-mgmt", "planned", "fixture"),
                        new ResourceSummary("CloudFront", "cx.chenyouyi.top", "planned", "fixture"),
                        new ResourceSummary("ECR", "album-mgmt", "ready", "fixture")),
                List.of(
                        new WorkflowSummary("album", "main", "passed", "fixture"),
                        new WorkflowSummary("album-mgmt", "main", "fixture", "fixture")));
    }
}
