package top.chenyouyi.album.mgmt.provider.dto;

public record WorkflowSummary(
        String repository,
        String branch,
        String status,
        String source) {
}
