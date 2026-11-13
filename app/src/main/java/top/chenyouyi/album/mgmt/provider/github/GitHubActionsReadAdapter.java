package top.chenyouyi.album.mgmt.provider.github;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.stream.StreamSupport;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import top.chenyouyi.album.mgmt.provider.dto.WorkflowSummary;

@Component
public class GitHubActionsReadAdapter {
    private final HttpClient client = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper;
    private final String apiUrl;
    private final String repository;
    private final String token;

    public GitHubActionsReadAdapter(
            ObjectMapper objectMapper,
            @Value("${GITHUB_API_URL:https://api.github.com}") String apiUrl,
            @Value("${GITHUB_REPOSITORY:}") String repository,
            @Value("${GITHUB_TOKEN:}") String token) {
        this.objectMapper = objectMapper;
        this.apiUrl = apiUrl;
        this.repository = repository;
        this.token = token;
    }

    public List<WorkflowSummary> workflows() {
        if (repository.isBlank() || token.isBlank()) {
            throw new IllegalStateException("GITHUB_REPOSITORY and GITHUB_TOKEN are required for provider profile");
        }
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl + "/repos/" + repository + "/actions/runs?per_page=5"))
                .header("Accept", "application/vnd.github+json")
                .header("Authorization", "Bearer " + token)
                .header("X-GitHub-Api-Version", "2022-11-28")
                .GET()
                .build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("GitHub Actions API returned HTTP " + response.statusCode());
            }
            JsonNode runs = objectMapper.readTree(response.body()).path("workflow_runs");
            return StreamSupport.stream(runs.spliterator(), false)
                    .map(run -> new WorkflowSummary(
                            repository,
                            run.path("head_branch").asText("unknown"),
                            run.path("conclusion").asText(run.path("status").asText("unknown")),
                            "github"))
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("GitHub Actions API request failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("GitHub Actions API request interrupted", e);
        }
    }
}
