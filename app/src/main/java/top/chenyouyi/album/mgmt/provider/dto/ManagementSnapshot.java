package top.chenyouyi.album.mgmt.provider.dto;

import java.util.List;

public record ManagementSnapshot(
        List<ResourceSummary> resources,
        List<WorkflowSummary> workflows) {
    public ManagementSnapshot {
        resources = List.copyOf(resources);
        workflows = List.copyOf(workflows);
    }
}
