package ai.mindvex.backend.service;

import ai.mindvex.backend.config.WatsonxConfig;
import ai.mindvex.backend.dto.WatsonxChatRequest;
import ai.mindvex.backend.dto.WatsonxChatResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class WatsonxService {

    private final WatsonxConfig config;
    private final WebClient orchestrateWebClient;

    // Token cache
    private String cachedToken;
    private LocalDateTime tokenExpiry;

    private static final int MAX_POLL_ATTEMPTS = 60;
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(2);

    private static final Map<String, String> AGENT_DISPLAY_NAMES = Map.of(
            "codebase-analysis", "MindVex Codebase Analyzer",
            "dependency-graph", "MindVex Dependency Mapper",
            "code-qa", "MindVex Code Q&A",
            "code-modifier", "MindVex Code Modifier",
            "code-review", "MindVex Code Reviewer",
            "documentation", "MindVex Documentation Generator",
            "git-assistant", "MindVex Git Assistant");

    /* ================= IAM TOKEN ================= */

    public String getAccessToken() {
        if (cachedToken != null && tokenExpiry != null &&
                LocalDateTime.now().isBefore(tokenExpiry)) {
            return cachedToken;
        }

        try {
            WebClient iamClient = WebClient.builder()
                    .baseUrl(config.getIamUrl())
                    .build();

            String formData =
                    "grant_type=urn:ibm:params:oauth:grant-type:apikey&apikey=" +
                            config.getApiKey();

            @SuppressWarnings("unchecked")
            Map<String, Object> response = iamClient.post()
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .bodyValue(formData)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            cachedToken = (String) response.get("access_token");
            int expiresIn = (Integer) response.getOrDefault("expires_in", 3600);
            tokenExpiry = LocalDateTime.now().plusSeconds(expiresIn - 300);

            return cachedToken;

        } catch (WebClientResponseException e) {
            throw new RuntimeException("Failed to authenticate with IBM IAM");
        }
    }

    /* ================= AGENT CALL ================= */

    public WatsonxChatResponse chat(WatsonxChatRequest request) {
        String agentType = request.getAgentId();

        try {
            String token = getAccessToken();
            String agentId = resolveAgentId(agentType);

            String userMessage = buildUserMessage(request);

            Map<String, Object> runRequest = Map.of(
    "input", Map.of(
        "messages", List.of(
            Map.of(
                "role", "user",
                "content", List.of(
                    Map.of(
                        "type", "text",
                        "text", userMessage
                    )
                )
            )
        )
    )
);


            String apiPath = "/api/v1/agents/" + agentId
               + "/runs?environment_id=" + config.getAgentEnvironmentId();


            @SuppressWarnings("unchecked")
            Map<String, Object> runResponse = orchestrateWebClient.post()
                    .uri(apiPath)
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(runRequest)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            String runId = (String) runResponse.get("id");
            String threadId = (String) runResponse.get("thread_id");

            return pollForCompletion(agentId, runId, threadId, agentType, token);

        } catch (Exception e) {
            return WatsonxChatResponse.error(agentType, e.getMessage());
        }
    }

    /* ================= POLLING ================= */

    private WatsonxChatResponse pollForCompletion(
            String agentId,
            String runId,
            String threadId,
            String agentType,
            String token) {

        for (int i = 0; i < MAX_POLL_ATTEMPTS; i++) {
            try {
                Thread.sleep(POLL_INTERVAL.toMillis());

                @SuppressWarnings("unchecked")
                Map<String, Object> statusResponse = orchestrateWebClient.get()
                        .uri("/api/v1/agents/{agentId}/runs/{runId}?environment_id={envId}",
     agentId, runId, config.getAgentEnvironmentId())
                        .header("Authorization", "Bearer " + token)
                        .retrieve()
                        .bodyToMono(Map.class)
                        .block();

                String status = (String) statusResponse.get("status");

                if ("completed".equalsIgnoreCase(status)) {
                    return extractAgentResponse(statusResponse, agentType, threadId);
                }

                if ("failed".equalsIgnoreCase(status)) {
                    return WatsonxChatResponse.error(agentType, "Agent run failed");
                }

            } catch (Exception ignored) {}
        }

        return WatsonxChatResponse.error(agentType, "Agent run timed out");
    }

    /* ================= RESPONSE ================= */

    private WatsonxChatResponse extractAgentResponse(
            Map<String, Object> runResponse,
            String agentType,
            String threadId) {

        String responseText = "";

        @SuppressWarnings("unchecked")
        Map<String, Object> output =
                (Map<String, Object>) runResponse.get("output");

        if (output != null && output.get("message") != null) {
            responseText = output.get("message").toString();
        }

        return WatsonxChatResponse.builder()
                .agentId(agentType)
                .response(responseText)
                .success(true)
                .timestamp(LocalDateTime.now())
                .metadata(Map.of("thread_id", threadId))
                .build();
    }

    /* ================= HELPERS ================= */

    private String buildUserMessage(WatsonxChatRequest request) {
        StringBuilder message = new StringBuilder();

        if (request.getFiles() != null && !request.getFiles().isEmpty()) {
            message.append("=== CODE CONTEXT ===\n\n");
            for (var file : request.getFiles()) {
                message.append("--- File: ").append(file.getPath()).append(" ---\n");
                message.append(file.getContent()).append("\n\n");
            }
        }

        message.append(request.getMessage());
        return message.toString();
    }

    private String resolveAgentId(String agentType) {
        if (config.getAgents() == null) return agentType;

        return switch (agentType) {
            case "codebase-analysis" -> config.getAgents().getCodebaseAnalyzer();
            case "dependency-graph" -> config.getAgents().getDependencyMapper();
            case "code-qa" -> config.getAgents().getCodeQa();
            case "code-modifier" -> config.getAgents().getCodeModifier();
            case "code-review" -> config.getAgents().getCodeReviewer();
            case "documentation" -> config.getAgents().getDocumentationGenerator();
            case "git-assistant" -> config.getAgents().getGitAssistant();
            default -> agentType;
        };
    }

    /* ================= UNCHANGED ================= */

    public List<Map<String, Object>> listAgents() {
        List<Map<String, Object>> agents = new ArrayList<>();

        for (var entry : AGENT_DISPLAY_NAMES.entrySet()) {
            String type = entry.getKey();
            String name = entry.getValue();
            String agentId = resolveAgentId(type);

            Map<String, Object> agent = new HashMap<>();
            agent.put("id", type);
            agent.put("name", name);
            agent.put("agentId", agentId);
            agent.put("configured", agentId != null && !agentId.isEmpty() && !agentId.equals(type));
            agents.add(agent);
        }
        return agents;
    }

    public Map<String, Object> checkHealth() {
        Map<String, Object> health = new HashMap<>();

        boolean hasApiKey = config.getApiKey() != null && !config.getApiKey().isEmpty();
        health.put("apiKeyConfigured", hasApiKey);
        health.put("orchestrateEndpoint", config.getOrchestrateEndpoint());

        Map<String, Boolean> agentStatus = new HashMap<>();
        for (var entry : AGENT_DISPLAY_NAMES.entrySet()) {
            String agentId = resolveAgentId(entry.getKey());
            agentStatus.put(entry.getKey(), agentId != null && !agentId.isEmpty() && !agentId.equals(entry.getKey()));
        }
        health.put("agents", agentStatus);

        try {
            getAccessToken();
            health.put("authenticated", true);
        } catch (Exception e) {
            health.put("authenticated", false);
        }

        return health;
    }
}