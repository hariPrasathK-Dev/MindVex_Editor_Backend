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

/**
 * Service for interacting with IBM watsonx Orchestrate Runtime API.
 * 
 * This service:
 * - Handles IBM IAM token exchange and caching
 * - Invokes deployed agents via Orchestrate Runtime API
 * - Passes user messages and context to agents
 * - Returns agent responses to the controller
 * 
 * Architecture:
 * Frontend → Backend (this service) → IAM Token → Orchestrate → Agent → Tools →
 * Backend
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WatsonxService {

    private final WatsonxConfig config;
    private final WebClient orchestrateWebClient;

    // Token cache
    private String cachedToken;
    private LocalDateTime tokenExpiry;

    // Polling configuration for agent runs
    private static final int MAX_POLL_ATTEMPTS = 60; // Max 60 attempts
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(2); // 2 seconds between polls

    /**
     * Agent type to name mapping for display purposes
     */
    private static final Map<String, String> AGENT_DISPLAY_NAMES = Map.of(
            "codebase-analysis", "MindVex Codebase Analyzer",
            "dependency-graph", "MindVex Dependency Mapper",
            "code-qa", "MindVex Code Q&A",
            "code-modifier", "MindVex Code Modifier",
            "code-review", "MindVex Code Reviewer",
            "documentation", "MindVex Documentation Generator",
            "git-assistant", "MindVex Git Assistant");

    /**
     * Get IBM IAM access token.
     * Exchanges the API key for an access token and caches it.
     * Token is refreshed 5 minutes before expiry.
     */
    public String getAccessToken() {
        // Return cached token if still valid
        if (cachedToken != null && tokenExpiry != null
                && LocalDateTime.now().isBefore(tokenExpiry)) {
            log.debug("Using cached IAM token");
            return cachedToken;
        }

        log.info("Fetching new IAM access token from IBM Cloud");

        if (config.getApiKey() == null || config.getApiKey().isEmpty()) {
            throw new RuntimeException("watsonx API key is not configured. Set WATSONX_API_KEY environment variable.");
        }

        try {
            WebClient iamClient = WebClient.builder()
                    .baseUrl(config.getIamUrl())
                    .build();

            String formData = "grant_type=urn:ibm:params:oauth:grant-type:apikey&apikey="
                    + config.getApiKey();

            @SuppressWarnings("unchecked")
            Map<String, Object> response = iamClient.post()
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .bodyValue(formData)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            if (response != null && response.containsKey("access_token")) {
                cachedToken = (String) response.get("access_token");
                int expiresIn = (Integer) response.getOrDefault("expires_in", 3600);
                // Refresh 5 minutes before actual expiry
                tokenExpiry = LocalDateTime.now().plusSeconds(expiresIn - 300);
                log.info("IAM access token obtained successfully, expires in {} seconds", expiresIn);
                return cachedToken;
            }

            throw new RuntimeException("Failed to obtain IAM access token - no token in response");

        } catch (WebClientResponseException e) {
            log.error("Failed to get IAM token: {} - {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new RuntimeException("Failed to authenticate with IBM Cloud: " + e.getMessage());
        }
    }

    /**
     * Invoke a deployed watsonx Orchestrate agent.
     * 
     * This method:
     * 1. Gets an IAM token
     * 2. Calls POST /api/v1/orchestrate/{agentId}/chat/completions
     * 3. Returns the agent response directly (synchronous)
     */
    public WatsonxChatResponse chat(WatsonxChatRequest request) {
        String agentType = request.getAgentId();
        log.info("Invoking watsonx Orchestrate agent: {}", agentType);

        try {
            String token = getAccessToken();
            log.info("IAM token obtained successfully");

            String agentId = resolveAgentId(agentType);
            log.info("Resolved agent ID: {} for type: {}", agentId, agentType);

            if (agentId == null || agentId.isEmpty()) {
                throw new RuntimeException("Agent ID not configured for: " + agentType +
                        ". Set WATSONX_AGENT_" + agentType.toUpperCase().replace("-", "_") + " environment variable.");
            }

            // Build user message with context
            String userMessage = buildUserMessage(request);
            log.info("User message: === CODE CONTEXT ===");

            // Create chat completions request (OpenAI-compatible format)
            Map<String, Object> chatRequest = new HashMap<>();
            chatRequest.put("stream", false);
            chatRequest.put("messages", List.of(
                    Map.of("role", "user", "content", userMessage)));

            String endpoint = config.getOrchestrateEndpoint();
            String apiPath = "/api/v1/orchestrate/" + agentId + "/chat/completions";
            log.info("Calling Orchestrate API: POST {}{}", endpoint, apiPath);

            // POST /api/v1/orchestrate/{agentId}/chat/completions
            @SuppressWarnings("unchecked")
            Map<String, Object> chatResponse = orchestrateWebClient.post()
                    .uri(apiPath)
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(chatRequest)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .doOnError(e -> log.error("WebClient error: {}", e.getMessage()))
                    .block();

            if (chatResponse == null) {
                throw new RuntimeException("No response from Orchestrate chat completions API");
            }

            log.info("Chat response received: {}", chatResponse.keySet());

            // Extract response from OpenAI-compatible format
            return extractChatCompletionResponse(chatResponse, agentType);

        } catch (WebClientResponseException e) {
            log.error("Orchestrate API error: status={}, body={}", e.getStatusCode(), e.getResponseBodyAsString());
            log.error("Request URL: {}", e.getRequest() != null ? e.getRequest().getURI() : "unknown");
            return WatsonxChatResponse.error(agentType,
                    "Orchestrate API error: " + e.getStatusCode() + " - " + e.getResponseBodyAsString());
        } catch (Exception e) {
            log.error("Error invoking agent {}: {}", agentType, e.getMessage(), e);
            return WatsonxChatResponse.error(agentType, e.getMessage());
        }
    }

    /**
     * Extract response from chat/completions format.
     * Response format: { "choices": [{ "message": { "role": "assistant", "content":
     * "..." } }] }
     */
    @SuppressWarnings("unchecked")
    private WatsonxChatResponse extractChatCompletionResponse(Map<String, Object> response, String agentType) {
        String responseText = "";
        String threadId = (String) response.get("thread_id");

        // Extract from choices array (OpenAI format)
        List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
        if (choices != null && !choices.isEmpty()) {
            Map<String, Object> firstChoice = choices.get(0);
            Map<String, Object> message = (Map<String, Object>) firstChoice.get("message");
            if (message != null) {
                responseText = (String) message.getOrDefault("content", "");
            }
        }

        if (responseText.isEmpty()) {
            log.warn("No content found in chat response: {}", response);
            responseText = "No response content received from the agent.";
        }

        log.info("Agent response extracted: {} chars, threadId={}", responseText.length(), threadId);

        return WatsonxChatResponse.builder()
                .agentId(agentType)
                .message(responseText)
                .status("completed")
                .toolCalls(new ArrayList<>())
                .metadata(Map.of(
                        "thread_id", threadId != null ? threadId : "",
                        "model", response.getOrDefault("model", "unknown")))
                .build();
    }

    /**
     * Poll for agent run completion.
     */
    private WatsonxChatResponse pollForCompletion(String agentId, String runId, String agentType, String token) {
        log.info("Polling for agent run completion: runId={}", runId);

        for (int attempt = 0; attempt < MAX_POLL_ATTEMPTS; attempt++) {
            try {
                Thread.sleep(POLL_INTERVAL.toMillis());

                @SuppressWarnings("unchecked")
                Map<String, Object> statusResponse = orchestrateWebClient.get()
                        .uri("/v1/agents/{agentId}/runs/{runId}", agentId, runId)
                        .header("Authorization", "Bearer " + token)
                        .retrieve()
                        .bodyToMono(Map.class)
                        .block();

                if (statusResponse == null) {
                    continue;
                }

                String status = (String) statusResponse.get("status");
                log.debug("Poll attempt {}: status={}", attempt + 1, status);

                if ("completed".equalsIgnoreCase(status)) {
                    return extractAgentResponse(statusResponse, agentType);
                } else if ("failed".equalsIgnoreCase(status)) {
                    String error = (String) statusResponse.getOrDefault("error", "Agent run failed");
                    return WatsonxChatResponse.error(agentType, error);
                } else if ("cancelled".equalsIgnoreCase(status)) {
                    return WatsonxChatResponse.error(agentType, "Agent run was cancelled");
                }
                // Status is still "running" or "pending", continue polling

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return WatsonxChatResponse.error(agentType, "Polling interrupted");
            } catch (Exception e) {
                log.warn("Poll attempt {} failed: {}", attempt + 1, e.getMessage());
            }
        }

        return WatsonxChatResponse.error(agentType, "Agent run timed out after " +
                (MAX_POLL_ATTEMPTS * POLL_INTERVAL.toSeconds()) + " seconds");
    }

    /**
     * Extract agent response from the run result.
     */
    private WatsonxChatResponse extractAgentResponse(Map<String, Object> runResponse, String agentType) {
        String responseText = "";
        List<WatsonxChatResponse.ToolCall> toolCalls = new ArrayList<>();

        // Extract output message
        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) runResponse.get("output");
        if (output != null) {
            Object message = output.get("message");
            if (message != null) {
                responseText = message.toString();
            }
        }

        // Extract tool calls if present
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) runResponse.get("steps");
        if (steps != null) {
            for (Map<String, Object> step : steps) {
                String type = (String) step.get("type");
                if ("tool_call".equalsIgnoreCase(type)) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> toolCall = (Map<String, Object>) step.get("tool_call");
                    if (toolCall != null) {
                        toolCalls.add(WatsonxChatResponse.ToolCall.builder()
                                .toolName((String) toolCall.get("name"))
                                .parameters((Map<String, Object>) toolCall.get("arguments"))
                                .result((String) step.get("output"))
                                .build());
                    }
                }
            }
        }

        // If no output message, try to get from last step
        if (responseText.isEmpty() && steps != null && !steps.isEmpty()) {
            Object lastStepOutput = steps.get(steps.size() - 1).get("output");
            if (lastStepOutput != null) {
                responseText = lastStepOutput.toString();
            }
        }

        return WatsonxChatResponse.builder()
                .id((String) runResponse.get("id"))
                .agentId(agentType)
                .response(responseText)
                .toolCalls(toolCalls.isEmpty() ? null : toolCalls)
                .success(true)
                .timestamp(LocalDateTime.now())
                .build();
    }

    /**
     * Build user message with file context if provided.
     */
    private String buildUserMessage(WatsonxChatRequest request) {
        StringBuilder message = new StringBuilder();

        // Add file context if provided
        if (request.getFiles() != null && !request.getFiles().isEmpty()) {
            message.append("=== CODE CONTEXT ===\n\n");
            for (var file : request.getFiles()) {
                message.append("--- File: ").append(file.getPath());
                if (file.getLanguage() != null) {
                    message.append(" (").append(file.getLanguage()).append(")");
                }
                message.append(" ---\n");
                message.append(file.getContent()).append("\n\n");
            }
            message.append("=== END CODE CONTEXT ===\n\n");
        }

        message.append(request.getMessage());
        return message.toString();
    }

    /**
     * Resolve agent type to deployed agent ID from configuration.
     */
    private String resolveAgentId(String agentType) {
        if (config.getAgents() == null) {
            log.warn("Agent IDs not configured. Using agent type as ID: {}", agentType);
            return agentType; // Fallback to using type as ID
        }

        return switch (agentType) {
            case "codebase-analysis" -> config.getAgents().getCodebaseAnalyzer();
            case "dependency-graph" -> config.getAgents().getDependencyMapper();
            case "code-qa", "qa-agent" -> config.getAgents().getCodeQa();
            case "code-modifier" -> config.getAgents().getCodeModifier();
            case "code-review" -> config.getAgents().getCodeReviewer();
            case "documentation" -> config.getAgents().getDocumentationGenerator();
            case "git-assistant", "pushing-agent" -> config.getAgents().getGitAssistant();
            default -> agentType; // Use as-is if no mapping
        };
    }

    /**
     * List available agents with their configuration status.
     */
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

    /**
     * Check watsonx Orchestrate health and configuration.
     */
    public Map<String, Object> checkHealth() {
        Map<String, Object> health = new HashMap<>();

        // Check API key
        boolean hasApiKey = config.getApiKey() != null && !config.getApiKey().isEmpty();
        health.put("apiKeyConfigured", hasApiKey);
        health.put("orchestrateEndpoint", config.getOrchestrateEndpoint());

        // Check agent configuration
        Map<String, Boolean> agentStatus = new HashMap<>();
        for (var entry : AGENT_DISPLAY_NAMES.entrySet()) {
            String agentId = resolveAgentId(entry.getKey());
            agentStatus.put(entry.getKey(), agentId != null && !agentId.isEmpty() && !agentId.equals(entry.getKey()));
        }
        health.put("agents", agentStatus);

        // Try to authenticate
        if (hasApiKey) {
            try {
                getAccessToken();
                health.put("authenticated", true);
            } catch (Exception e) {
                health.put("authenticated", false);
                health.put("authError", e.getMessage());
            }
        } else {
            health.put("authenticated", false);
            health.put("authError", "API key not configured");
        }

        return health;
    }
}
