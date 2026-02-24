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
    public String generateWiki(Long userId, String repoUrl) {
        log.info("[LivingWiki] Generating wiki for user={} repo={}", userId, repoUrl);

        // Gather context from code intelligence tables
        var deps = depRepo.findByUserIdAndRepoUrl(userId, repoUrl);
        long embeddingCount = embeddingRepo.countByUserIdAndRepoUrl(userId, repoUrl);

        // Build a structural summary
        Map<String, Set<String>> moduleFiles = new LinkedHashMap<>();
        Set<String> allFiles = new HashSet<>();
        for (var dep : deps) {
            allFiles.add(dep.getSourceFile());
            allFiles.add(dep.getTargetFile());
            String module = extractModule(dep.getSourceFile());
            moduleFiles.computeIfAbsent(module, k -> new LinkedHashSet<>()).add(dep.getSourceFile());
        }

        StringBuilder context = new StringBuilder();
        context.append("Repository: ").append(repoUrl).append("\n");
        context.append("Total files: ").append(allFiles.size()).append("\n");
        context.append("Total dependencies: ").append(deps.size()).append("\n");
        context.append("Total code chunks embedded: ").append(embeddingCount).append("\n\n");
        context.append("Module Structure:\n");

        for (var entry : moduleFiles.entrySet()) {
            context.append("- ").append(entry.getKey())
                    .append(": ").append(entry.getValue().size()).append(" files\n");
            // List first 5 files per module
            entry.getValue().stream().limit(5).forEach(f ->
                    context.append("  - ").append(f).append("\n"));
            if (entry.getValue().size() > 5) {
                context.append("  - ... and ").append(entry.getValue().size() - 5).append(" more\n");
            }
        }

        // Generate with Gemini if API key available
        if (geminiApiKey != null && !geminiApiKey.isBlank()) {
            try {
                return callGeminiForWiki(context.toString());
            } catch (Exception e) {
                log.warn("[LivingWiki] Gemini failed, returning structured summary: {}", e.getMessage());
            }
        }

        // Fallback: return the structured summary as markdown
        return generateFallbackWiki(repoUrl, moduleFiles, allFiles.size(), deps.size());
    }

    /**
     * Generate a description for a specific module/directory.
     */
    public String describeModule(Long userId, String repoUrl, String modulePath) {
        // Find relevant code chunks
        List<VectorEmbedding> chunks = embeddingService.semanticSearch(
                userId, repoUrl, "What does the " + modulePath + " module do?", 5
        );

        if (!chunks.isEmpty()) {
            String codeContext = chunks.stream()
                    .map(c -> "// " + c.getFilePath() + " (chunk " + c.getChunkIndex() + ")\n" + c.getChunkText())
                    .collect(Collectors.joining("\n\n"));

            if (geminiApiKey != null && !geminiApiKey.isBlank()) {
                try {
                    return callGeminiForModuleDescription(modulePath, codeContext);
                } catch (Exception e) {
                    log.warn("[LivingWiki] Gemini failed for module description: {}", e.getMessage());
                }
            }

            return "## " + modulePath + "\n\nThis module contains " + chunks.size() + " code chunks.\n\n"
                    + "Files:\n" + chunks.stream().map(c -> "- " + c.getFilePath()).distinct().collect(Collectors.joining("\n"));
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
            return "No data found for module: **" + modulePath + "**. Make sure the repository has been analyzed via Analytics → Mine first.";
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

        if (geminiApiKey != null && !geminiApiKey.isBlank()) {
            try {
                return callGeminiForModuleDescription(modulePath, context.toString());
            } catch (Exception e) {
                log.warn("[LivingWiki] Gemini fallback failed for module: {}", e.getMessage());
            }
        }

        return "## " + modulePath + "\n\nThis module contains " + moduleFiles.size() + " files.\n\n"
                + "Files:\n" + moduleFiles.stream().limit(20).map(f -> "- `" + f + "`").collect(Collectors.joining("\n"));
    }

    // ─── Gemini API Calls ───────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private String callGeminiForWiki(String structureContext) {
        String prompt = """
                You are a technical documentation writer. Based on the following repository structure,
                generate a comprehensive wiki overview in Markdown format. Include:
                1. A project overview section
                2. Module descriptions with their purposes
                3. Key architectural patterns observed
                4. Dependency relationships between modules
                
                Be concise but informative. Use Markdown headers, lists, and code blocks where appropriate.
                
                Repository Structure:
                """ + structureContext;

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
        String url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=" + geminiApiKey;

        Map<String, Object> body = Map.of(
                "contents", List.of(Map.of(
                        "parts", List.of(Map.of("text", prompt))
                ))
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<Map> response = restTemplate.exchange(
                url, HttpMethod.POST, new HttpEntity<>(body, headers), Map.class
        );

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
        if (parts.length <= 1) return "(root)";
        return parts[0]; // top-level directory
    }

    private String generateFallbackWiki(String repoUrl, Map<String, Set<String>> modules, int totalFiles, int totalDeps) {
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
            entry.getValue().stream().limit(10).forEach(f ->
                    wiki.append("- `").append(f).append("`\n"));
            if (entry.getValue().size() > 10) {
                wiki.append("- *... and ").append(entry.getValue().size() - 10).append(" more*\n");
            }
            wiki.append("\n");
        }

        return wiki.toString();
    }
}
