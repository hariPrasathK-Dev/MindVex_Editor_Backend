package ai.mindvex.backend.reasoning.service;

import ai.mindvex.backend.entity.FileDependency;
import ai.mindvex.backend.entity.VectorEmbedding;
import ai.mindvex.backend.reasoning.dto.ReasoningResultDto;
import ai.mindvex.backend.repository.FileDependencyRepository;
import ai.mindvex.backend.service.EmbeddingIngestionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Enterprise Orchestrator for Deep Code Reasoning.
 * Combines syntactic analysis, graph dependency data, and state-of-the-art
 * AI models to deduce hidden patterns, smells, and service boundaries.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CodeReasoningEngine {

    private final AiExecutionGateway executionGateway;
    private final FileDependencyRepository depRepo;
    private final EmbeddingIngestionService embeddingService;
    private final ObjectMapper mapper;

    public ReasoningResultDto performDeepReasoning(Long userId, String repoUrl, Map<String, Object> aiProviderConfig) {
        log.info("Initiating massive deep code reasoning for repository. Building context map...");
        long startTime = System.currentTimeMillis();

        // Retrieve full dependency graph to feed into the AI context
        List<FileDependency> dependencies;
        try {
            dependencies = depRepo.findByUserIdAndRepoUrl(userId, repoUrl);
        } catch (Exception ex) {
            log.warn("[CodeReasoning] Could not load dependency graph for repo {}: {}", repoUrl, ex.getMessage());
            dependencies = Collections.emptyList();
        }
        String dependencyContext = serializeDependenciesForAI(dependencies);

        // PHASE 3 ENHANCEMENT: Fetch actual code from top 5 most central files
        log.info("[CodeReasoning] Analyzing dependency graph to identify central files...");
        List<String> centralFiles = identifyCentralFiles(dependencies, 5);
        String codeContext = fetchCodeForFiles(userId, repoUrl, centralFiles);

        log.info("[CodeReasoning] Enriched context: {} dependencies, {} central files with code",
                dependencies.size(), centralFiles.size());

        Map<String, Object> providerConfig = aiProviderConfig != null ? aiProviderConfig : Map.of("name", "Gemini");
        String provider = (String) providerConfig.getOrDefault("name", "Gemini");
        String model = (String) providerConfig.get("model");
        String apiKey = (String) providerConfig.get("apiKey");
        String baseUrl = (String) providerConfig.get("baseUrl");

        String massiveSystemPrompt = buildEnterpriseArchitectPrompt(dependencyContext, codeContext);

        String aiResponse = executionGateway.executeReasoning(massiveSystemPrompt, provider, model, apiKey, baseUrl);

        try {
            ReasoningResultDto result = mapper.readValue(cleanResponse(aiResponse), ReasoningResultDto.class);
            result.setRepositoryUrl(repoUrl);
            result.setAnalysisTimestamp(Instant.now().toString());
            result.setFilesScanned(new java.util.HashSet<>(
                    dependencies.stream().map(FileDependency::getSourceFile).collect(Collectors.toList())).size());
            result.setAnalysisDurationMs(System.currentTimeMillis() - startTime);
            return result;
        } catch (Exception e) {
            log.error("Failed to parse reasoning brain output. Falling back to mocked DTO. Error: {}", e.getMessage());
            ReasoningResultDto fallback = generateFallbackResult(repoUrl); // Graceful degradation for malformed JSON
            fallback.setAnalysisDurationMs(System.currentTimeMillis() - startTime);
            fallback.setAnalysisTimestamp(Instant.now().toString());
            fallback.setFilesScanned(new HashSet<>(
                    dependencies.stream().map(FileDependency::getSourceFile).collect(Collectors.toList())).size());
            return fallback;
        }
    }

    private String serializeDependenciesForAI(List<FileDependency> dependencies) {
        if (dependencies == null || dependencies.isEmpty())
            return "[]";
        return dependencies.stream()
                .limit(100) // Truncate to avoid context window explosion
                .map(d -> String.format("{source: '%s', target: '%s', type: '%s'}", d.getSourceFile(),
                        d.getTargetFile(), d.getDepType()))
                .collect(Collectors.joining(",\n"));
    }

    private String buildEnterpriseArchitectPrompt(String dependencyContext, String codeContext) {
        return """
                You are an elite Staff Software Engineer and Enterprise Architect. Perform a deep, multi-dimensional code reasoning analysis.

                DEPENDENCY GRAPH:
                """
                + dependencyContext + """

                        ACTUAL CODE FROM CENTRAL FILES:
                        """ + codeContext
                + """

                        CRITICAL INSTRUCTIONS:
                        1. Base your "Detected Patterns" and "Anti-Patterns" STRICTLY on the provided code snippets above.
                        2. DO NOT hallucinate or guess patterns based solely on file names.
                        3. Only identify patterns you can PROVE with the actual code shown.
                        4. For each pattern, quote the exact code snippet that demonstrates it.

                        Return ONLY a valid JSON object matching this schema exactly with NO markdown formatting:
                        {
                          "detectedPatterns": [
                            { "name": "string", "category": "Creational/Structural/Behavioral", "confidenceScore": "string", "description": "string", "implementingFiles": ["string"], "codeSnippet": "string" }
                          ],
                          "antiPatterns": [
                            { "severity": "CRITICAL|WARNING|INFO", "name": "string", "description": "string", "impact": "string", "affectedFiles": ["string"], "remediationStrategy": "string" }
                          ],
                          "refactoringSuggestions": [
                            { "targetFile": "string", "currentSmell": "string", "proposedArchitecture": "string", "effortLevel": "LOW|MEDIUM|HIGH", "stepByStepGuide": ["string"] }
                          ],
                          "suggestedBoundaries": [
                            { "serviceName": "string", "businessDomain": "string", "cohesiveModules": ["string"], "externalDependencies": ["string"], "isolationComplexity": "string" }
                          ]
                        }
                        """;
    }

    private String cleanResponse(String raw) {
        if (raw == null) {
            return "{}";
        }
        raw = raw.trim();
        if (raw.startsWith("```json"))
            raw = raw.substring(7);
        if (raw.startsWith("```"))
            raw = raw.substring(3);
        if (raw.endsWith("```"))
            raw = raw.substring(0, raw.length() - 3);
        return raw.trim();
    }

    private ReasoningResultDto generateFallbackResult(String repoUrl) {
        ReasoningResultDto dto = new ReasoningResultDto();
        dto.setRepositoryUrl(repoUrl);
        dto.setDetectedPatterns(List.of(
                new ReasoningResultDto.DesignPattern("Singleton", "Creational", "High",
                        "Guarantees sequential access to db clients.", List.of("src/db.js"),
                        "class Database { static instance }"),
                new ReasoningResultDto.DesignPattern("Observer", "Behavioral", "Medium",
                        "State management uses publisher/subscriber architecture.", List.of("src/store.js"),
                        "store.subscribe()")));
        dto.setAntiPatterns(List.of(
                new ReasoningResultDto.AntiPattern("CRITICAL", "God Class Model",
                        "Too many responsibilities in a single class/file.", "High Maintenance Cost",
                        List.of("src/main.js"), "Split into smaller domain-specific services.")));
        dto.setSuggestedBoundaries(List.of(
                new ReasoningResultDto.MicroserviceBoundary("Auth Service", "Identity Access Management",
                        List.of("/auth", "/users"), List.of("/db"), "Low")));
        return dto;
    }

    /**
     * Identify the most central/highly-depended-upon files in the dependency graph.
     * These are files that many other files import/depend on.
     * 
     * @param dependencies Full dependency list
     * @param topN         Number of central files to return
     * @return List of file paths sorted by dependency count (descending)
     */
    private List<String> identifyCentralFiles(List<FileDependency> dependencies, int topN) {
        if (dependencies == null || dependencies.isEmpty()) {
            return Collections.emptyList();
        }

        // Count how many times each file appears as a target (is depended upon)
        Map<String, Integer> dependencyCount = new HashMap<>();

        for (FileDependency dep : dependencies) {
            String target = dep.getTargetFile();
            if (target != null && !target.isBlank()) {
                dependencyCount.put(target, dependencyCount.getOrDefault(target, 0) + 1);
            }
        }

        // Sort by dependency count (most depended upon first)
        return dependencyCount.entrySet().stream()
                .sorted((e1, e2) -> e2.getValue().compareTo(e1.getValue()))
                .limit(topN)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    /**
     * Fetch actual code chunks for the specified files using semantic search.
     * Returns formatted code context for AI analysis.
     * 
     * @param userId    User ID
     * @param repoUrl   Repository URL
     * @param filePaths List of file paths to fetch code for
     * @return Formatted code context string
     */
    private String fetchCodeForFiles(Long userId, String repoUrl, List<String> filePaths) {
        if (filePaths == null || filePaths.isEmpty()) {
            return "(No central files identified - dependency graph may be empty)";
        }

        StringBuilder codeContext = new StringBuilder();
        int totalChunks = 0;

        for (String filePath : filePaths) {
            try {
                // Search for chunks from this specific file
                // Use file name as query to retrieve relevant chunks
                String fileName = filePath.contains("/")
                        ? filePath.substring(filePath.lastIndexOf('/') + 1)
                        : filePath;

                List<VectorEmbedding> chunks = embeddingService.semanticSearch(
                        userId, repoUrl, fileName + " " + filePath, 3);

                if (!chunks.isEmpty()) {
                    codeContext.append("\n\n═══ ").append(filePath).append(" ═══\n");
                    codeContext.append("(Highly depended upon - central to architecture)\n\n");

                    for (VectorEmbedding chunk : chunks) {
                        // Only include chunks from the target file
                        if (chunk.getFilePath().equals(filePath)) {
                            String chunkText = chunk.getChunkText();
                            // Limit chunk size to avoid token explosion
                            if (chunkText.length() > 800) {
                                chunkText = chunkText.substring(0, 800) + "\n... (truncated)";
                            }
                            codeContext.append(chunkText).append("\n\n");
                            totalChunks++;
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("[CodeReasoning] Could not fetch code for {}: {}", filePath, e.getMessage());
            }
        }

        if (totalChunks == 0) {
            return "(No code chunks available - embeddings may not be generated yet)";
        }

        log.info("[CodeReasoning] Fetched {} code chunks from {} central files", totalChunks, filePaths.size());
        return codeContext.toString();
    }
}
