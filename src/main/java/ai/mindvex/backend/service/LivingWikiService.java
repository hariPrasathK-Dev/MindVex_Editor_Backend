package ai.mindvex.backend.service;

import ai.mindvex.backend.entity.VectorEmbedding;
import ai.mindvex.backend.repository.FileDependencyRepository;
import ai.mindvex.backend.repository.VectorEmbeddingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Living Wiki Generator
 *
 * Uses Gemini to generate module-level descriptions and architecture overviews
 * based on the code intelligence data (file dependencies + embeddings).
 *
 * Produces structured wiki content after every analysis run.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LivingWikiService {

    private final FileDependencyRepository depRepo;
    private final VectorEmbeddingRepository embeddingRepo;
    private final EmbeddingIngestionService embeddingService;
    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${gemini.api-key:#{null}}")
    private String geminiApiKey;

    /**
     * Generate a module-level wiki overview for a repository.
     * Returns Markdown-formatted content describing the project structure.
     */
    public Map<String, String> generateWiki(Long userId, String repoUrl, Map<String, Object> provider) {
        log.info("[LivingWiki] Generating wiki for user={} repo={}", userId, repoUrl);

        // Gather context from code intelligence tables — these may not exist yet
        List<?> deps = List.of();
        long embeddingCount = 0;
        Map<String, Set<String>> moduleFiles = new LinkedHashMap<>();
        Set<String> allFiles = new HashSet<>();

        try {
            deps = depRepo.findByUserIdAndRepoUrl(userId, repoUrl);
            for (var dep : deps) {
                var d = (ai.mindvex.backend.entity.FileDependency) dep;
                allFiles.add(d.getSourceFile());
                allFiles.add(d.getTargetFile());
                String module = extractModule(d.getSourceFile());
                moduleFiles.computeIfAbsent(module, k -> new LinkedHashSet<>()).add(d.getSourceFile());
            }
        } catch (Exception e) {
            log.warn("[LivingWiki] Could not load dependency graph (table may not exist yet): {}", e.getMessage());
        }

        try {
            embeddingCount = embeddingRepo.countByUserIdAndRepoUrl(userId, repoUrl);
        } catch (Exception e) {
            log.warn("[LivingWiki] Could not load embedding count (table may not exist yet): {}", e.getMessage());
        }

        // ─── Semantic Context Retrieval ─────────────────────────────────────
        // Use embeddings to gather rich semantic context for documentation
        StringBuilder semanticContext = new StringBuilder();
        if (embeddingCount > 0) {
            try {
                log.info("[LivingWiki] Retrieving semantic context via embeddings (count={})", embeddingCount);
                
                // Multi-aspect semantic search for comprehensive understanding
                String[] queries = {
                    "main entry point, startup, initialization, configuration",
                    "API endpoints, routes, controllers, request handlers",
                    "data models, entities, database schema, repositories",
                    "business logic, services, core functionality, algorithms",
                    "authentication, authorization, security, validation"
                };
                
                Set<String> seenChunks = new HashSet<>();
                int totalChunks = 0;
                
                for (String query : queries) {
                    try {
                        List<VectorEmbedding> chunks = embeddingService.semanticSearch(userId, repoUrl, query, 5);
                        for (VectorEmbedding chunk : chunks) {
                            String chunkId = chunk.getFilePath() + ":" + chunk.getChunkIndex();
                            if (!seenChunks.contains(chunkId)) {
                                seenChunks.add(chunkId);
                                semanticContext.append("\n// ").append(chunk.getFilePath())
                                    .append(" (chunk ").append(chunk.getChunkIndex()).append(")\n")
                                    .append(chunk.getChunkText()).append("\n");
                                totalChunks++;
                            }
                        }
                    } catch (Exception e) {
                        log.warn("[LivingWiki] Semantic search failed for query '{}': {}", query, e.getMessage());
                    }
                }
                
                log.info("[LivingWiki] Retrieved {} unique code chunks for semantic context", totalChunks);
            } catch (Exception e) {
                log.warn("[LivingWiki] Could not retrieve semantic context: {}", e.getMessage());
            }
        }

        // Build a structural summary
        StringBuilder context = new StringBuilder();
        context.append("Repository: ").append(repoUrl).append("\n");
        context.append("Total files: ").append(allFiles.size()).append("\n");
        context.append("Total dependencies: ").append(deps.size()).append("\n");
        context.append("Total code chunks embedded: ").append(embeddingCount).append("\n\n");
        context.append("Module Structure:\n");

        if (moduleFiles.isEmpty()) {
            context.append("(Repository has not been indexed yet — generating documentation from repo URL)\n");
        } else {
            for (var entry : moduleFiles.entrySet()) {
                context.append("- ").append(entry.getKey())
                        .append(": ").append(entry.getValue().size()).append(" files\n");
                entry.getValue().stream().limit(5).forEach(f -> context.append("  - ").append(f).append("\n"));
                if (entry.getValue().size() > 5) {
                    context.append("  - ... and ").append(entry.getValue().size() - 5).append(" more\n");
                }
            }
        }

        // Append semantic code context if available
        if (semanticContext.length() > 0) {
            context.append("\nRelevant Code Samples (from semantic analysis):\n");
            context.append(semanticContext.toString());
        }

        if (provider != null) {
            try {
                String aiResponse = callAiForWiki(context.toString(), provider);
                Map<String, String> files = parseResponse(aiResponse);
                if (files.size() > 1)
                    return files;
                log.warn("[LivingWiki] Provider '{}' returned only 1 file, trying Gemini", provider.get("name"));
            } catch (Exception e) {
                log.warn("[LivingWiki] Provider '{}' failed: {}", provider.get("name"), e.getMessage());
            }
        }

        // Generate with Gemini if API key available
        if (geminiApiKey != null && !geminiApiKey.isBlank()) {
            try {
                return parseResponse(callGeminiForWiki(context.toString()));
            } catch (Exception e) {
                log.warn("[LivingWiki] Gemini failed: {}", e.getMessage());
            }
        }

        // Static fallback
        log.info("[LivingWiki] Using static fallback for repo={}", repoUrl);
        return Map.of("README.md", generateFallbackWiki(repoUrl, moduleFiles, allFiles.size(), deps.size()));
    }

    private Map<String, String> parseResponse(String response) {
        if (response == null || response.isBlank()) {
            return Map.of("README.md", "No content generated.");
        }

        // ── Strategy 1: Delimiter format (===FILE: name===) ──────────────────
        // This is the primary format we ask LLMs to use: 100% newline-safe.
        String DELIM = "===FILE:";
        if (response.contains(DELIM)) {
            Map<String, String> files = new LinkedHashMap<>();
            String[] sections = response.split("(?m)^===FILE:");
            for (String section : sections) {
                if (section.isBlank())
                    continue;
                int lineEnd = section.indexOf('\n');
                if (lineEnd < 0)
                    continue;
                String filename = section.substring(0, lineEnd).replace("===", "").trim();
                String content = section.substring(lineEnd + 1);
                // Strip trailing delimiter line or markdown fence if present
                if (content.endsWith("==="))
                    content = content.substring(0, content.lastIndexOf("==="));
                content = content.trim();
                if (!filename.isBlank() && !content.isBlank()) {
                    files.put(filename, content);
                }
            }
            if (files.size() > 1) {
                log.info("[LivingWiki] Parsed {} files via delimiter format", files.size());
                return files;
            }
        }

        // ── Strategy 2: JSON parsing (try several sub-strategies) ────────────
        ObjectMapper mapper = new ObjectMapper();
        String[] candidates = {
                response.trim(),
                // Strip ```json ... ``` fences
                response.trim().replaceAll("(?s)^```(?:json)?\\s*", "").replaceAll("```\\s*$", "").trim()
        };
        for (String candidate : candidates) {
            // Find outermost { ... }
            int fb = candidate.indexOf('{');
            int lb = candidate.lastIndexOf('}');
            if (fb >= 0 && lb > fb) {
                try {
                    Map<String, String> result = mapper.readValue(
                            candidate.substring(fb, lb + 1),
                            new TypeReference<Map<String, String>>() {
                            });
                    if (result.size() > 1) {
                        log.info("[LivingWiki] Parsed {} files via JSON", result.size());
                        return result;
                    }
                } catch (Exception ignored) {
                }
            }
        }

        log.warn("[LivingWiki] All parse strategies failed, using raw content as README.md (len={})",
                response.length());
        return Map.of("README.md", response);
    }

    // repairTruncatedJson kept for legacy JSON fallback
    @SuppressWarnings("unused")
    private String repairTruncatedJson(String json) {
        int open = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escape) {
                escape = false;
                continue;
            }
            if (c == '\\' && inString) {
                escape = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (!inString) {
                if (c == '{')
                    open++;
                else if (c == '}')
                    open--;
            }
        }
        StringBuilder sb = new StringBuilder(json);
        if (inString)
            sb.append('"');
        for (int i = 0; i < open; i++)
            sb.append('}');
        return sb.toString();
    }

    /**
     * Generate a description for a specific module/directory.
     */
    public String describeModule(Long userId, String repoUrl, String modulePath, Map<String, Object> provider) {
        // Find relevant code chunks
        List<VectorEmbedding> chunks = embeddingService.semanticSearch(
                userId, repoUrl, "What does the " + modulePath + " module do?", 5);

        if (!chunks.isEmpty()) {
            String codeContext = chunks.stream()
                    .map(c -> "// " + c.getFilePath() + " (chunk " + c.getChunkIndex() + ")\n" + c.getChunkText())
                    .collect(Collectors.joining("\n\n"));

            if (provider != null) {
                try {
                    return callAiForModuleDescription(modulePath, codeContext, provider);
                } catch (Exception e) {
                    log.warn("[LivingWiki] Provider failed for module description: {}", e.getMessage());
                }
            }

            if (geminiApiKey != null && !geminiApiKey.isBlank()) {
                try {
                    return callGeminiForModuleDescription(modulePath, codeContext);
                } catch (Exception e) {
                    log.warn("[LivingWiki] Gemini failed for module description: {}", e.getMessage());
                }
            }

            return "## " + modulePath + "\n\nThis module contains " + chunks.size() + " code chunks.\n\n"
                    + "Files:\n"
                    + chunks.stream().map(c -> "- " + c.getFilePath()).distinct().collect(Collectors.joining("\n"));
        }

        // Fallback: use file dependency data to describe the module
        var deps = depRepo.findByUserIdAndRepoUrl(userId, repoUrl);
        var moduleFiles = deps.stream()
                .flatMap(d -> java.util.stream.Stream.of(d.getSourceFile(), d.getTargetFile()))
                .filter(f -> f.startsWith(modulePath) || f.contains("/" + modulePath + "/") || f.contains(modulePath))
                .distinct()
                .sorted()
                .collect(Collectors.toList());

        if (moduleFiles.isEmpty()) {
            return "No data found for module: **" + modulePath
                    + "**. Make sure the repository has been analyzed via Analytics → Mine first.";
        }

        // Build context from dependency data
        StringBuilder context = new StringBuilder();
        context.append("Module: ").append(modulePath).append("\n");
        context.append("Files found: ").append(moduleFiles.size()).append("\n\n");
        for (String file : moduleFiles.stream().limit(15).collect(Collectors.toList())) {
            context.append("- ").append(file).append("\n");
            var fileDeps = deps.stream()
                    .filter(d -> d.getSourceFile().equals(file))
                    .map(d -> "  imports: " + d.getTargetFile())
                    .limit(3)
                    .collect(Collectors.toList());
            fileDeps.forEach(fd -> context.append("  ").append(fd).append("\n"));
        }

        if (provider != null) {
            try {
                return callAiForModuleDescription(modulePath, context.toString(), provider);
            } catch (Exception e) {
                log.warn("[LivingWiki] Provider fallback failed for module: {}", e.getMessage());
            }
        }

        if (geminiApiKey != null && !geminiApiKey.isBlank()) {
            try {
                return callGeminiForModuleDescription(modulePath, context.toString());
            } catch (Exception e) {
                log.warn("[LivingWiki] Gemini fallback failed for module: {}", e.getMessage());
            }
        }

        return "## " + modulePath + "\n\nThis module contains " + moduleFiles.size() + " files.\n\n"
                + "Files:\n"
                + moduleFiles.stream().limit(20).map(f -> "- `" + f + "`").collect(Collectors.joining("\n"));
    }

    // ─── General AI Provider Calls
    // ───────────────────────────────────────────────────

    private String callAiForWiki(String structureContext, Map<String, Object> provider) {
        String prompt = """
                You are a senior technical documentation engineer specializing in industrial-grade codebase documentation.
                Generate a comprehensive, deep-dive documentation suite for this repository.

                For EACH file, output it in this EXACT format:
                ===FILE: <filename>===
                <file content here>

                Files to generate:
                1. README.md — Professional overview including high-level architecture, tech stack (with versions), quick start, and detailed module breakdowns. Include ASCII diagrams for simple flows.
                2. adr.md — Formal Architecture Decision Records. Document at least 5 key decisions with status, context, decision, and consequences.
                3. api-reference.md — Exhaustive API documentation. For every endpoint/service, include base URL, auth, exhaustive parameter tables, example requests/responses, and error codes.
                4. architecture.md — Industrial-grade system design. Describe design patterns (SOLID, GoF), state management, security model, and data flow. Use deep technical analysis.
                5. documentation-health.md — Detailed health report. Analyze coverage, clarity, and consistency. Provide a health score (X out of 100).
                6. api-descriptions.json — Schema-compliant JSON of all endpoints.
                7. doc_snapshot.json — Project statistics, module counts, and health tier.
                8. tree.txt — Professional ASCII tree.
                9. tree.json — Structured hierarchical map for visual exploration.
                10. architecture-graph.json — A JSON object for interactive graph visualization: { "nodes": [{"id": "...", "label": "...", "type": "module/component"}], "edges": [{"source": "...", "target": "...", "label": "..."}] }

                REQUIREMENTS:
                - Content must be RICH and DETAILED. Avoid placeholders.
                - Use professional, industrial-grade terminology.
                - For diagrams, use clean, well-aligned ASCII art.
                - DO NOT wrap in markdown code blocks. Output starts with ===FILE: README.md===

                Repository Structure:
                """
                + structureContext;

        return executeAiCall(prompt, provider);
    }

    private String callAiForModuleDescription(String modulePath, String codeContext, Map<String, Object> provider) {
        String prompt = """
                Analyze the following code from the '%s' module and generate a concise description.
                Include: what it does, key classes/functions, and how it relates to other parts of the codebase.

                Code:
                %s
                """.formatted(modulePath, codeContext.length() > 4000 ? codeContext.substring(0, 4000) : codeContext);

        return executeAiCall(prompt, provider);
    }

    @SuppressWarnings("unchecked")
    private String executeAiCall(String prompt, Map<String, Object> provider) {
        String providerName = (String) provider.get("name");
        String model = (String) provider.get("model");
        String apiKey = (String) provider.get("apiKey");
        String baseUrl = (String) provider.get("baseUrl");

        if ("Ollama".equals(providerName)) {
            String url = (baseUrl != null && !baseUrl.isEmpty() ? baseUrl : "http://localhost:11434") + "/api/chat";
            Map<String, Object> request = Map.of(
                    "model", model != null ? model : "llama3",
                    "messages", List.of(Map.of("role", "user", "content", prompt)),
                    "stream", false);
            return extractReply(restTemplate.postForEntity(url, request, Map.class), providerName);

        } else if ("Anthropic".equals(providerName)) {
            String url = "https://api.anthropic.com/v1/messages";
            Map<String, Object> request = Map.of(
                    "model", model != null ? model : "claude-3-5-sonnet-20240620",
                    "messages", List.of(Map.of("role", "user", "content", prompt)),
                    "max_tokens", 4096);
            HttpHeaders headers = new HttpHeaders();
            headers.set("x-api-key", apiKey);
            headers.set("anthropic-version", "2023-06-01");
            return extractReply(
                    restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(request, headers), Map.class),
                    providerName);

        } else if ("Groq".equals(providerName) || "OpenAI".equals(providerName) || "XAI".equals(providerName)
                || "LMStudio".equals(providerName)) {
            // Determine correct URL first (before using baseUrl)
            String url;
            if ("Groq".equals(providerName))
                url = "https://api.groq.com/openai/v1/chat/completions";
            else if ("XAI".equals(providerName))
                url = "https://api.x.ai/v1/chat/completions";
            else if ("OpenAI".equals(providerName))
                url = "https://api.openai.com/v1/chat/completions";
            else if ("LMStudio".equals(providerName))
                url = (baseUrl != null && !baseUrl.isEmpty() ? baseUrl : "http://localhost:1234")
                        + "/v1/chat/completions";
            else
                url = (baseUrl != null && !baseUrl.isEmpty() ? baseUrl : "http://localhost:11434")
                        + "/v1/chat/completions";

            // Null-safe model
            String safeModel = (model != null && !model.isBlank()) ? model : "llama3";
            Map<String, Object> request = new java.util.LinkedHashMap<>();
            request.put("model", safeModel);
            request.put("messages", List.of(Map.of("role", "user", "content", prompt)));
            request.put("max_tokens", 8000); // Must be large enough for full JSON
            request.put("temperature", 0.3); // Lower temp = more predictable JSON output
            HttpHeaders headers = new HttpHeaders();
            if (apiKey != null && !apiKey.isBlank())
                headers.setBearerAuth(apiKey);
            headers.setContentType(MediaType.APPLICATION_JSON);
            return extractReply(
                    restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(request, headers), Map.class),
                    providerName);
        }

        if ("Google".equals(providerName) || "Gemini".equals(providerName)) {
            // Route through Gemini with the supplied apiKey
            String key = apiKey != null && !apiKey.isBlank() ? apiKey : geminiApiKey;
            if (key == null || key.isBlank())
                throw new RuntimeException("Google/Gemini API key not configured");
            String url = "https://generativelanguage.googleapis.com/v1beta/models/"
                    + (model != null ? model : "gemini-2.0-flash") + ":generateContent?key=" + key;
            Map<String, Object> body = Map.of(
                    "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))));
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            @SuppressWarnings("unchecked")
            ResponseEntity<Map> resp = restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers),
                    Map.class);
            @SuppressWarnings("unchecked")
            var candidates = (List<Map<String, Object>>) resp.getBody().get("candidates");
            @SuppressWarnings("unchecked")
            var parts2 = (List<Map<String, Object>>) ((Map<String, Object>) candidates.get(0).get("content"))
                    .get("parts");
            return (String) parts2.get(0).get("text");
        }

        throw new RuntimeException("Unsupported AI Provider: " + providerName);
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private String extractReply(ResponseEntity<Map> response, String providerName) {
        try {
            Map<String, Object> body = response.getBody();
            if (body == null)
                throw new RuntimeException("Empty response body from " + providerName);

            if ("Ollama".equals(providerName)) {
                return (String) ((Map<String, Object>) body.get("message")).get("content");
            } else if ("Anthropic".equals(providerName)) {
                return (String) ((Map<String, Object>) ((List<?>) body.get("content")).get(0)).get("text");
            } else { // OpenAI-like schemas (Groq, OpenAI, XAI, LMStudio)
                return (String) ((Map<String, Object>) ((Map<String, Object>) ((List<?>) body
                        .get("choices")).get(0)).get("message")).get("content");
            }
        } catch (Exception e) {
            log.error("[LivingWiki] Failed to extract reply from {}: {}", providerName, e.getMessage());
            throw new RuntimeException("Failed to parse " + providerName + " response: " + e.getMessage());
        }
    }

    // ─── Gemini Legacy Defaults
    // ───────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private String callGeminiForWiki(String structureContext) {
        String prompt = """
                You are a senior technical documentation engineer. Generate industrial-grade documentation files for this repository.

                For EACH file, use this EXACT format:
                ===FILE: <filename>===
                <file content here>

                Files to generate:
                1. README.md — Professional overview, tech stack, setup
                2. adr.md — Formal Architecture Decision Records (at least 5)
                3. api-reference.md — Exhaustive API endpoint documentation
                4. architecture.md — Industrial-grade system design & design patterns
                5. documentation-health.md — Detailed health score (X out of 100)
                6. api-descriptions.json — Schema-compliant endpoint JSON
                7. doc_snapshot.json — Project stats and health tier
                8. tree.txt — Professional ASCII directory tree
                9. tree.json — Hierarchical map for visual exploration
                10. architecture-graph.json — Graph nodes and edges for visualization: { "nodes": [{"id": "...", "label": "...", "type": "module"}], "edges": [{"source": "...", "target": "...", "label": "..."}] }

                Repository Structure:
                """
                + structureContext;

        return callGemini(prompt);
    }

    @SuppressWarnings("unchecked")
    private String callGeminiForModuleDescription(String modulePath, String codeContext) {
        String prompt = """
                Analyze the following code from the '%s' module and generate a concise description.
                Include: what it does, key classes/functions, and how it relates to other parts of the codebase.

                Code:
                %s
                """.formatted(modulePath, codeContext.length() > 4000 ? codeContext.substring(0, 4000) : codeContext);

        return callGemini(prompt);
    }

    @SuppressWarnings("unchecked")
    private String callGemini(String prompt) {
        String url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key="
                + geminiApiKey;

        Map<String, Object> body = Map.of(
                "contents", List.of(Map.of(
                        "parts", List.of(Map.of("text", prompt)))));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<Map> response = restTemplate.exchange(
                url, HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);

        var candidates = (List<Map<String, Object>>) response.getBody().get("candidates");
        if (candidates != null && !candidates.isEmpty()) {
            var content = (Map<String, Object>) candidates.get(0).get("content");
            var parts = (List<Map<String, Object>>) content.get("parts");
            if (parts != null && !parts.isEmpty()) {
                return (String) parts.get(0).get("text");
            }
        }

        return "Could not generate wiki content.";
    }

    // ─── Helpers ────────────────────────────────────────────────────────────

    private String extractModule(String filePath) {
        String[] parts = filePath.split("[/\\\\]");
        if (parts.length <= 1)
            return "(root)";
        return parts[0]; // top-level directory
    }

    private String generateFallbackWiki(String repoUrl, Map<String, Set<String>> modules, int totalFiles,
            int totalDeps) {
        StringBuilder wiki = new StringBuilder();
        wiki.append("# Project Wiki\n\n");
        wiki.append("**Repository:** ").append(repoUrl).append("\n\n");
        wiki.append("## Overview\n\n");
        wiki.append("This project contains **").append(totalFiles).append(" files** across **")
                .append(modules.size()).append(" modules** with **")
                .append(totalDeps).append(" dependency relationships**.\n\n");
        wiki.append("## Modules\n\n");

        for (var entry : modules.entrySet()) {
            wiki.append("### ").append(entry.getKey()).append("\n\n");
            wiki.append("Contains ").append(entry.getValue().size()).append(" files:\n\n");
            entry.getValue().stream().limit(10).forEach(f -> wiki.append("- `").append(f).append("`\n"));
            if (entry.getValue().size() > 10) {
                wiki.append("- *... and ").append(entry.getValue().size() - 10).append(" more*\n");
            }
            wiki.append("\n");
        }

        return wiki.toString();
    }
}
