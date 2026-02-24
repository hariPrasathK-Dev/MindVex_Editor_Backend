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
    public ResponseEntity<Map<String, Object>> aiChat(
            @RequestParam String repoUrl,
            @RequestBody Map<String, Object> body,
            Authentication authentication) {

        Long userId = extractUserId(authentication);
        String message = (String) body.getOrDefault("message", "");
        List<Map<String, String>> history = (List<Map<String, String>>) body.getOrDefault("history", List.of());

        if (geminiApiKey == null || geminiApiKey.isBlank()) {
            return ResponseEntity.ok(Map.of(
                    "reply", "AI chat is not available — Gemini API key is not configured.",
                    "model", "none"
            ));
        }

        // Gather codebase context for the AI
        var deps = depRepo.findByUserIdAndRepoUrl(userId, repoUrl);
        Set<String> allFiles = new LinkedHashSet<>();
        Map<String, Set<String>> modules = new LinkedHashMap<>();
        deps.forEach(d -> {
            allFiles.add(d.getSourceFile());
            allFiles.add(d.getTargetFile());
            String module = d.getSourceFile().contains("/")
                    ? d.getSourceFile().substring(0, d.getSourceFile().indexOf("/"))
                    : "(root)";
            modules.computeIfAbsent(module, k -> new LinkedHashSet<>()).add(d.getSourceFile());
        });

        StringBuilder codebaseContext = new StringBuilder();
        codebaseContext.append("Repository: ").append(repoUrl).append("\n");
        codebaseContext.append("Total files: ").append(allFiles.size()).append("\n");
        codebaseContext.append("Total dependency edges: ").append(deps.size()).append("\n");
        codebaseContext.append("Modules: ").append(modules.keySet()).append("\n\n");

        // Add module summary
        modules.forEach((mod, files) -> {
            codebaseContext.append("- ").append(mod).append(": ")
                    .append(files.size()).append(" files");
            if (files.size() <= 5) {
                codebaseContext.append(" (").append(String.join(", ", files)).append(")");
            }
            codebaseContext.append("\n");
        });

        // Try to find relevant code via semantic search
        List<VectorEmbedding> relevantCode = embeddingService.semanticSearch(userId, repoUrl, message, 3);
        if (!relevantCode.isEmpty()) {
            codebaseContext.append("\nRelevant code snippets:\n");
            relevantCode.forEach(chunk -> {
                codebaseContext.append("\n// ").append(chunk.getFilePath()).append("\n");
                String text = chunk.getChunkText();
                codebaseContext.append(text.length() > 500 ? text.substring(0, 500) + "..." : text).append("\n");
            });
        }

        // Build Gemini request with conversation history
        List<Map<String, Object>> contents = new ArrayList<>();

        // System context as first user message
        contents.add(Map.of("role", "user", "parts", List.of(Map.of("text",
                "You are MindVex AI, an intelligent code assistant embedded in a code editor. " +
                "You help developers understand, navigate, and work with their codebase. " +
                "Be concise, helpful, and format your responses in Markdown. " +
                "Use code blocks with language specifiers when showing code.\n\n" +
                "Here is the codebase context:\n" + codebaseContext
        ))));
        contents.add(Map.of("role", "model", "parts", List.of(Map.of("text",
                "I'm MindVex AI, ready to help you with your codebase. I have context about the repository structure, " +
                "modules, and dependencies. How can I assist you?"
        ))));

        // Add conversation history
        for (Map<String, String> msg : history) {
            String role = "user".equals(msg.get("role")) ? "user" : "model";
            contents.add(Map.of("role", role, "parts", List.of(Map.of("text", msg.getOrDefault("content", "")))));
        }

        // Add current message
        contents.add(Map.of("role", "user", "parts", List.of(Map.of("text", message))));

        try {
            String url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=" + geminiApiKey;

            Map<String, Object> geminiBody = Map.of("contents", contents);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            ResponseEntity<Map> response = restTemplate.exchange(
                    url, HttpMethod.POST, new HttpEntity<>(geminiBody, headers), Map.class
            );

            var candidates = (List<Map<String, Object>>) response.getBody().get("candidates");
            if (candidates != null && !candidates.isEmpty()) {
                var content = (Map<String, Object>) candidates.get(0).get("content");
                var parts = (List<Map<String, Object>>) content.get("parts");
                if (parts != null && !parts.isEmpty()) {
                    String reply = (String) parts.get(0).get("text");
                    return ResponseEntity.ok(Map.of(
                            "reply", reply,
                            "model", "gemini-2.0-flash",
                            "contextFiles", allFiles.size(),
                            "contextDeps", deps.size()
                    ));
                }
            }

            return ResponseEntity.ok(Map.of(
                    "reply", "I couldn't generate a response. Please try rephrasing your question.",
                    "model", "gemini-2.0-flash"
            ));

        } catch (Exception e) {
            log.error("[McpChat] Gemini call failed: {}", e.getMessage());
            return ResponseEntity.ok(Map.of(
                    "reply", "⚠️ AI service temporarily unavailable: " + e.getMessage(),
                    "model", "error"
            ));
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
