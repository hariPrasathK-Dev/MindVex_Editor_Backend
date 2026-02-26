package ai.mindvex.backend.controller;

import ai.mindvex.backend.entity.User;
import ai.mindvex.backend.entity.VectorEmbedding;
import ai.mindvex.backend.repository.FileDependencyRepository;
import ai.mindvex.backend.repository.UserRepository;
import ai.mindvex.backend.service.EmbeddingIngestionService;
import ai.mindvex.backend.service.LivingWikiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Model Context Protocol (MCP) Server
 *
 * Provides AI coding assistants direct access to the code intelligence
 * stored in PostgreSQL. This enables AI to reason over:
 * - File dependency graphs
 * - Semantic code search (via pgvector embeddings)
 * - Living wiki / project documentation
 * - Module descriptions
 * - AI-powered code chat (Gemini)
 */
@RestController
@RequestMapping("/api/mcp")
@RequiredArgsConstructor
@Slf4j
public class McpController {

    private final EmbeddingIngestionService embeddingService;
    private final LivingWikiService wikiService;
    private final FileDependencyRepository depRepo;
    private final UserRepository userRepository;

    @Value("${gemini.api-key:#{null}}")
    private String geminiApiKey;

    private final RestTemplate restTemplate = new RestTemplate();

    // ─── Resource Discovery ─────────────────────────────────────────────────

    @GetMapping("/resources")
    public ResponseEntity<Map<String, Object>> listResources(
            @RequestParam String repoUrl,
            Authentication authentication) {

        Long userId = extractUserId(authentication);

        var deps = depRepo.findByUserIdAndRepoUrl(userId, repoUrl);
        Set<String> files = new HashSet<>();
        deps.forEach(d -> {
            files.add(d.getSourceFile());
            files.add(d.getTargetFile());
        });

        return ResponseEntity.ok(Map.of(
                "server", "mindvex-mcp",
                "version", "1.0",
                "resources", List.of(
                        Map.of("type", "dependency_graph", "fileCount", files.size(), "edgeCount", deps.size()),
                        Map.of("type", "semantic_search", "description", "Vector similarity search over code chunks"),
                        Map.of("type", "wiki", "description", "AI-generated project documentation"),
                        Map.of("type", "module_description", "description", "Detailed module-level descriptions"),
                        Map.of("type", "ai_chat", "description", "Gemini-powered code chat assistant")
                ),
                "tools", List.of("search", "deps", "wiki", "describe", "chat")
        ));
    }

    // ─── Tool: Semantic Search ──────────────────────────────────────────────

    @PostMapping("/tools/search")
    public ResponseEntity<Map<String, Object>> semanticSearch(
            @RequestParam String repoUrl,
            @RequestBody Map<String, Object> body,
            Authentication authentication) {

        Long userId = extractUserId(authentication);
        String query = (String) body.getOrDefault("query", "");
        int topK = (int) body.getOrDefault("topK", 5);

        List<VectorEmbedding> results = embeddingService.semanticSearch(userId, repoUrl, query, topK);

        List<Map<String, Object>> matches = results.stream().map(r -> Map.<String, Object>of(
                "filePath", r.getFilePath(),
                "chunkIndex", r.getChunkIndex(),
                "content", r.getChunkText()
        )).collect(Collectors.toList());

        return ResponseEntity.ok(Map.of(
                "query", query,
                "results", matches,
                "totalMatches", results.size()
        ));
    }

    // ─── Tool: Dependency Tree ──────────────────────────────────────────────

    @PostMapping("/tools/deps")
    public ResponseEntity<Map<String, Object>> getDependencyTree(
            @RequestParam String repoUrl,
            @RequestBody Map<String, Object> body,
            Authentication authentication) {

        Long userId = extractUserId(authentication);
        String filePath = (String) body.getOrDefault("filePath", "");

        var allDeps = depRepo.findByUserIdAndRepoUrl(userId, repoUrl);

        if (!filePath.isBlank()) {
            // Filter to deps involving this file
            var fileDeps = allDeps.stream()
                    .filter(d -> d.getSourceFile().equals(filePath) || d.getTargetFile().equals(filePath))
                    .map(d -> Map.<String, String>of(
                            "source", d.getSourceFile(),
                            "target", d.getTargetFile(),
                            "type", d.getDepType()
                    ))
                    .collect(Collectors.toList());

            return ResponseEntity.ok(Map.of(
                    "file", filePath,
                    "dependencies", fileDeps,
                    "count", fileDeps.size()
            ));
        }

        // Return full graph summary
        return ResponseEntity.ok(Map.of(
                "totalEdges", allDeps.size(),
                "files", allDeps.stream()
                        .flatMap(d -> List.of(d.getSourceFile(), d.getTargetFile()).stream())
                        .distinct()
                        .collect(Collectors.toList())
        ));
    }

    // ─── Tool: Wiki Generation ──────────────────────────────────────────────

    @PostMapping("/tools/wiki")
    public ResponseEntity<Map<String, Object>> generateWiki(
            @RequestParam String repoUrl,
            Authentication authentication) {

        Long userId = extractUserId(authentication);
        String wiki = wikiService.generateWiki(userId, repoUrl);

        return ResponseEntity.ok(Map.of(
                "repoUrl", repoUrl,
                "content", wiki,
                "format", "markdown"
        ));
    }

    // ─── Tool: Module Description ───────────────────────────────────────────

    @PostMapping("/tools/describe")
    public ResponseEntity<Map<String, Object>> describeModule(
            @RequestParam String repoUrl,
            @RequestBody Map<String, Object> body,
            Authentication authentication) {

        Long userId = extractUserId(authentication);
        String modulePath = (String) body.getOrDefault("module", "");

        String description = wikiService.describeModule(userId, repoUrl, modulePath);

        return ResponseEntity.ok(Map.of(
                "module", modulePath,
                "description", description,
                "format", "markdown"
        ));
    }

    // ─── Tool: AI Chat ──────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    @PostMapping("/tools/chat")
    public ResponseEntity<Map<String, Object>> chat(
            @RequestParam String repoUrl,
            @RequestBody Map<String, Object> body,
            Authentication authentication) {

        Long userId = extractUserId(authentication);
        String message = (String) body.getOrDefault("message", "");
        List<Map<String, String>> history = (List<Map<String, String>>) body.getOrDefault("history", List.of());
        Map<String, Object> provider = (Map<String, Object>) body.get("provider");

        // ─── Context Gathering ──────────────────────────────────────────────────

        var deps = depRepo.findByUserIdAndRepoUrl(userId, repoUrl);
        Set<String> allFiles = new HashSet<>();
        deps.forEach(d -> {
            allFiles.add(d.getSourceFile());
            allFiles.add(d.getTargetFile());
        });

        String codebaseContext = String.format(
                "Repository: %s\nFiles: %d\nDependencies: %d\nModules: %s",
                repoUrl,
                allFiles.size(),
                deps.size(),
                allFiles.stream().limit(20).collect(Collectors.joining(", "))
        );

        // ─── Provider Selection ─────────────────────────────────────────────────

        if (provider != null) {
            String providerName = (String) provider.get("name");
            String model = (String) provider.get("model");
            String apiKey = (String) provider.get("apiKey");
            String baseUrl = (String) provider.get("baseUrl");

            try {
                if ("Ollama".equals(providerName)) {
                    return callOllama(message, history, model, baseUrl, codebaseContext);
                } else if ("LMStudio".equals(providerName)) {
                    return callOpenAILike(providerName, message, history, model, baseUrl != null ? baseUrl : "http://localhost:1234", apiKey, codebaseContext);
                } else if ("Anthropic".equals(providerName)) {
                    return callAnthropic(message, history, model, apiKey, codebaseContext);
                } else if ("Groq".equals(providerName) || "OpenAI".equals(providerName) || "XAI".equals(providerName)) {
                    return callOpenAILike(providerName, message, history, model, apiKey, baseUrl, codebaseContext);
                } else if ("Google".equals(providerName)) {
                    return callGemini(message, history, model, apiKey, codebaseContext);
                }
            } catch (Exception e) {
                log.error("[McpChat] {} call failed: {}", providerName, e.getMessage());
                return ResponseEntity.ok(Map.of(
                        "reply", "⚠️ AI service (" + providerName + ") unavailable: " + e.getMessage(),
                        "model", "error"
                ));
            }
        }

        // Default to Gemini if no provider specified or recognized
        return callGemini(message, history, "gemini-2.0-flash", geminiApiKey, codebaseContext);
    }

    private ResponseEntity<Map<String, Object>> callGemini(String message, List<Map<String, String>> history, String model, String apiKey, String context) {
        if (apiKey == null || apiKey.isEmpty()) {
            return ResponseEntity.ok(Map.of("reply", "⚠️ Google API key not configured.", "model", "error"));
        }

        List<Map<String, Object>> contents = new ArrayList<>();
        contents.add(Map.of("role", "user", "parts", List.of(Map.of("text",
                "System: You are MindVex AI. Provide technical codebase analysis.\n" +
                "Context: " + context
        ))));
        contents.add(Map.of("role", "model", "parts", List.of(Map.of("text", "Understood. I have the context."))));

        for (Map<String, String> msg : history) {
            String role = "user".equals(msg.get("role")) ? "user" : "model";
            contents.add(Map.of("role", role, "parts", List.of(Map.of("text", msg.getOrDefault("content", "")))));
        }
        contents.add(Map.of("role", "user", "parts", List.of(Map.of("text", message))));

        String url = "https://generativelanguage.googleapis.com/v1beta/models/" + (model != null ? model : "gemini-2.0-flash") + ":generateContent?key=" + apiKey;
        
        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(url, Map.of("contents", contents), Map.class);
            var candidates = (List<Map<String, Object>>) response.getBody().get("candidates");
            var content = (Map<String, Object>) candidates.get(0).get("content");
            var parts = (List<Map<String, Object>>) content.get("parts");
            String reply = (String) parts.get(0).get("text");
            return ResponseEntity.ok(Map.of("reply", reply, "model", model));
        } catch (Exception e) {
            throw new RuntimeException("Gemini call failed: " + e.getMessage());
        }
    }

    private ResponseEntity<Map<String, Object>> callOllama(String message, List<Map<String, String>> history, String model, String baseUrl, String context) {
        String url = (baseUrl != null ? baseUrl : "http://localhost:11434") + "/api/chat";
        
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", "You are MindVex AI. Context: " + context));
        for (Map<String, String> msg : history) {
            messages.add(Map.of("role", msg.get("role"), "content", msg.get("content")));
        }
        messages.add(Map.of("role", "user", "content", message));

        Map<String, Object> request = Map.of(
            "model", model != null ? model : "llama3",
            "messages", messages,
            "stream", false
        );

        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(url, request, Map.class);
            String reply = (String) ((Map<String, Object>) response.getBody().get("message")).get("content");
            return ResponseEntity.ok(Map.of("reply", reply, "model", model));
        } catch (Exception e) {
            throw new RuntimeException("Ollama call failed: " + e.getMessage());
        }
    }

    private ResponseEntity<Map<String, Object>> callAnthropic(String message, List<Map<String, String>> history, String model, String apiKey, String context) {
        if (apiKey == null || apiKey.isEmpty()) {
            return ResponseEntity.ok(Map.of("reply", "⚠️ Anthropic API key not configured.", "model", "error"));
        }

        String url = "https://api.anthropic.com/v1/messages";
        
        List<Map<String, String>> messages = new ArrayList<>();
        for (Map<String, String> msg : history) {
            messages.add(Map.of("role", msg.get("role"), "content", msg.get("content")));
        }
        messages.add(Map.of("role", "user", "content", message));

        Map<String, Object> request = Map.of(
            "model", model != null ? model : "claude-3-5-sonnet-20240620",
            "system", "You are MindVex AI. Context: " + context,
            "messages", messages,
            "max_tokens", 4096
        );

        HttpHeaders headers = new HttpHeaders();
        headers.set("x-api-key", apiKey);
        headers.set("anthropic-version", "2023-06-01");
        headers.setContentType(MediaType.APPLICATION_JSON);

        try {
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(request, headers), Map.class);
            String reply = (String) ((Map<String, Object>) ((List) response.getBody().get("content")).get(0)).get("text");
            return ResponseEntity.ok(Map.of("reply", reply, "model", model));
        } catch (Exception e) {
            throw new RuntimeException("Anthropic call failed: " + e.getMessage());
        }
    }

    private ResponseEntity<Map<String, Object>> callOpenAILike(String provider, String message, List<Map<String, String>> history, String model, String apiKey, String baseUrl, String context) {
        if (apiKey == null || apiKey.isEmpty()) {
            return ResponseEntity.ok(Map.of("reply", "⚠️ " + provider + " API key not configured.", "model", "error"));
        }

        String url;
        if ("Groq".equals(provider)) url = "https://api.groq.com/openai/v1/chat/completions";
        else if ("XAI".equals(provider)) url = "https://api.x.ai/v1/chat/completions";
        else if ("OpenAI".equals(provider)) url = "https://api.openai.com/v1/chat/completions";
        else url = baseUrl + "/v1/chat/completions";

        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", "You are MindVex AI. Context: " + context));
        for (Map<String, String> msg : history) {
            messages.add(Map.of("role", msg.get("role"), "content", msg.get("content")));
        }
        messages.add(Map.of("role", "user", "content", message));

        Map<String, Object> request = Map.of(
            "model", model,
            "messages", messages
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(apiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);

        try {
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(request, headers), Map.class);
            String reply = (String) ((Map<String, Object>) ((Map<String, Object>) ((List) response.getBody().get("choices")).get(0)).get("message")).get("content");
            return ResponseEntity.ok(Map.of("reply", reply, "model", model));
        } catch (Exception e) {
            throw new RuntimeException(provider + " call failed: " + e.getMessage());
        }
    }

    // ─── Helpers ────────────────────────────────────────────────────────────

    private Long extractUserId(Authentication authentication) {
        UserDetails userDetails = (UserDetails) authentication.getPrincipal();
        User user = userRepository.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new RuntimeException("User not found"));
        return user.getId();
    }
}
