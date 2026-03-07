package ai.mindvex.backend.service;

import ai.mindvex.backend.dto.EndpointParameter;
import ai.mindvex.backend.dto.ErrorResponse;
import ai.mindvex.backend.dto.ExtractedEndpoint;
import ai.mindvex.backend.entity.User;
import ai.mindvex.backend.entity.VectorEmbedding;
import ai.mindvex.backend.repository.FileDependencyRepository;
import ai.mindvex.backend.repository.UserRepository;
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
    private final UserRepository userRepository;
    private final GitHubApiService githubApiService;
    private final DataCleaningService dataCleaningService;
    private final DocumentFormattingService documentFormattingService;
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
        int apiChunksCount = 0; // Track API-related chunks for conditional generation
        
        if (embeddingCount > 0) {
            try {
                log.info("[LivingWiki] Retrieving semantic context via embeddings (count={})", embeddingCount);

                // ═══ ENHANCED MULTI-STAGE SEARCH FOR COMPREHENSIVE API ANALYSIS ═══

                // Stage 1: General codebase understanding (keep original limits)
                String[] generalQueries = {
                        "main entry point, startup, initialization",
                        "data models, entities, database schema",
                        "configuration, settings, environment variables"
                };

                Set<String> seenChunks = new HashSet<>();
                int totalChunks = 0;
                int maxGeneralChunks = 8;
                int maxChunkLength = 600;

                for (String query : generalQueries) {
                    if (totalChunks >= maxGeneralChunks)
                        break;

                    try {
                        List<VectorEmbedding> chunks = embeddingService.semanticSearch(userId, repoUrl, query, 2);
                        for (VectorEmbedding chunk : chunks) {
                            if (totalChunks >= maxGeneralChunks)
                                break;

                            String chunkId = chunk.getFilePath() + ":" + chunk.getChunkIndex();
                            if (!seenChunks.contains(chunkId)) {
                                seenChunks.add(chunkId);
                                String chunkText = chunk.getChunkText();
                                if (chunkText.length() > maxChunkLength) {
                                    chunkText = chunkText.substring(0, maxChunkLength) + "...";
                                }
                                semanticContext.append("\n// ").append(chunk.getFilePath())
                                        .append(" (chunk ").append(chunk.getChunkIndex()).append(")\n")
                                        .append(chunkText).append("\n");
                                totalChunks++;
                            }
                        }
                    } catch (Exception e) {
                        log.warn("[LivingWiki] General search failed for query '{}': {}", query, e.getMessage());
                    }
                }

                // Stage 2: COMPREHENSIVE API ROUTE/ENDPOINT DISCOVERY
                // Search for framework-specific route patterns with HIGHER chunk limits
                String[] apiQueries = {
                        // CRITICAL: Router prefix registration (MUST come first for context)
                        "APIRouter prefix include_router app.include_router FastAPI",
                        "Blueprint url_prefix register_blueprint Flask",
                        "app.use router Express middleware mounting",
                        "@RequestMapping Controller base path prefix Spring",
                        "main.py app.py __init__.py router registration",

                        // Python frameworks
                        "@app.route decorator Flask blueprint endpoint",
                        "FastAPI @app.get @app.post @app.put @app.delete APIRouter",
                        "Django urls.py path route urlpatterns views",

                        // JavaScript/TypeScript frameworks
                        "Express router.get router.post app.use middleware",
                        "Next.js API route handler export async function",
                        "NestJS @Controller @Get @Post decorator",

                        // Java/Kotlin frameworks
                        "@RestController @RequestMapping @GetMapping @PostMapping",
                        "@Path @GET @POST JAX-RS REST endpoint",

                        // Go frameworks
                        "http.HandleFunc router.GET router.POST gin.Engine",

                        // Ruby frameworks
                        "Rails routes.rb get post put delete",

                        // Generic route patterns
                        "API endpoint route handler controller view",
                        "REST HTTP GET POST PUT DELETE PATCH",
                        "authentication login register auth route"
                };

                // apiChunksCount already declared at method scope
                int maxApiChunks = 40; // SIGNIFICANTLY HIGHER for API documentation
                int maxRouteChunkLength = 1500; // DON'T truncate route definitions aggressively

                semanticContext.append("\n\n═══ API ROUTES & ENDPOINTS ═══\n");

                for (String query : apiQueries) {
                    if (apiChunksCount >= maxApiChunks)
                        break;

                    try {
                        // Retrieve MORE chunks per query for comprehensive coverage
                        List<VectorEmbedding> chunks = embeddingService.semanticSearch(userId, repoUrl, query, 4);
                        for (VectorEmbedding chunk : chunks) {
                            if (apiChunksCount >= maxApiChunks)
                                break;

                            String chunkId = chunk.getFilePath() + ":" + chunk.getChunkIndex();
                            if (!seenChunks.contains(chunkId)) {
                                seenChunks.add(chunkId);
                                String chunkText = chunk.getChunkText();

                                // Check if this looks like a route/controller file OR main entry point
                                String filePath = chunk.getFilePath().toLowerCase();
                                boolean isRouteFile = filePath.contains("route") || filePath.contains("controller")
                                        || filePath.contains("endpoint") || filePath.contains("api")
                                        || filePath.contains("view") || filePath.contains("handler");

                                // Main files often contain router registration - preserve them fully
                                boolean isMainFile = filePath.contains("main.py") || filePath.contains("app.py")
                                        || filePath.contains("__init__.py") || filePath.contains("index.ts")
                                        || filePath.contains("server.ts") || filePath.contains("application.java");

                                // Don't truncate route files or main files aggressively
                                int maxLen = (isRouteFile || isMainFile) ? maxRouteChunkLength : maxChunkLength;
                                if (chunkText.length() > maxLen) {
                                    chunkText = chunkText.substring(0, maxLen) + "\n... (truncated)";
                                }

                                semanticContext.append("\n// ").append(chunk.getFilePath())
                                        .append(" (chunk ").append(chunk.getChunkIndex()).append(")\n")
                                        .append(chunkText).append("\n");
                                apiChunksCount++;
                                totalChunks++;
                            }
                        }
                    } catch (Exception e) {
                        log.warn("[LivingWiki] API search failed for query '{}': {}", query, e.getMessage());
                    }
                }

                log.info("[LivingWiki] Retrieved {} total chunks ({} general + {} API) for comprehensive context",
                        totalChunks, totalChunks - apiChunksCount, apiChunksCount);
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

        // ─── GitHub Architecture Decision Context ──────────────────────────
        // Fetch architectural decisions from GitHub (commits, PRs, issues)
        String githubToken = getUserGithubToken(userId);
        if (githubToken != null && !githubToken.isBlank()) {
            try {
                log.info("[LivingWiki] Fetching architecture context from GitHub");
                GitHubApiService.ArchitectureContext archContext = githubApiService.fetchArchitectureContext(repoUrl,
                        githubToken);

                if (!archContext.isEmpty()) {
                    context.append("\n─── GitHub Architecture Decision Records ───\n\n");

                    // Add architectural commits
                    if (!archContext.getCommits().isEmpty()) {
                        context.append("## Architectural Commits\n");
                        context.append("Recent commits with architectural significance:\n\n");
                        archContext.getCommits().stream().limit(10).forEach(commit -> {
                            context.append(String.format("- **%s** (%s)\n",
                                    commit.getMessage().split("\n")[0], // First line only
                                    commit.getDate() != null ? commit.getDate().substring(0, 10) : "unknown"));
                            context.append(String.format("  Author: %s | URL: %s\n",
                                    commit.getAuthor(), commit.getUrl()));
                        });
                        context.append("\n");
                    }

                    // Add architectural pull requests
                    if (!archContext.getPullRequests().isEmpty()) {
                        context.append("## Architectural Pull Requests\n");
                        context.append("Pull requests discussing design decisions:\n\n");
                        archContext.getPullRequests().stream().limit(8).forEach(pr -> {
                            context.append(String.format("- **PR #%d: %s** [%s]\n",
                                    pr.getNumber(), pr.getTitle(), pr.getState()));
                            if (!pr.getLabels().isEmpty()) {
                                context.append(String.format("  Labels: %s\n",
                                        String.join(", ", pr.getLabels())));
                            }
                            if (pr.getBody() != null && !pr.getBody().isBlank()) {
                                String description = pr.getBody().length() > 150
                                        ? pr.getBody().substring(0, 150) + "..."
                                        : pr.getBody();
                                context.append(String.format("  Description: %s\n", description));
                            }
                            context.append(String.format("  URL: %s\n", pr.getUrl()));
                        });
                        context.append("\n");
                    }

                    // Add architectural issues
                    if (!archContext.getIssues().isEmpty()) {
                        context.append("## Architectural Issues & Discussions\n");
                        context.append("Issues discussing architecture and design:\n\n");
                        archContext.getIssues().stream().limit(8).forEach(issue -> {
                            context.append(String.format("- **Issue #%d: %s** [%s]\n",
                                    issue.getNumber(), issue.getTitle(), issue.getState()));
                            if (!issue.getLabels().isEmpty()) {
                                context.append(String.format("  Labels: %s\n",
                                        String.join(", ", issue.getLabels())));
                            }
                            if (issue.getBody() != null && !issue.getBody().isBlank()) {
                                String description = issue.getBody().length() > 150
                                        ? issue.getBody().substring(0, 150) + "..."
                                        : issue.getBody();
                                context.append(String.format("  Description: %s\n", description));
                            }
                            context.append(String.format("  URL: %s\n", issue.getUrl()));
                        });
                        context.append("\n");
                    }

                    log.info("[LivingWiki] Added GitHub architecture context ({} commits, {} PRs, {} issues)",
                            archContext.getCommits().size(),
                            archContext.getPullRequests().size(),
                            archContext.getIssues().size());
                }

            } catch (Exception e) {
                log.warn("[LivingWiki] Could not fetch GitHub architecture context: {}", e.getMessage());
            }
        } else {
            log.info("[LivingWiki] No GitHub token available, skipping architecture context from GitHub");
        }

        // ═══════════════════════════════════════════════════════════════════════
        // GENERATE EACH DOCUMENTATION FILE SEPARATELY TO AVOID PAYLOAD TOO LARGE
        // ═══════════════════════════════════════════════════════════════════════

        Map<String, String> documentationFiles = new LinkedHashMap<>();

        try {
            log.info("[LivingWiki] Generating documentation files separately to avoid payload size issues");

            // File 1: README.md (general understanding)
            log.info("[LivingWiki] [1/3] Generating README.md...");
            String readmeContext = buildReadmeContext(context.toString(), semanticContext.toString());
            String readme = generateSingleFile("README.md", readmeContext, provider);
            if (readme != null && !readme.isBlank()) {
                documentationFiles.put("README.md", readme);
                log.info("[LivingWiki] ✓ README.md generated ({} chars)", readme.length());
            }

            // File 2: ADR.md (architecture decisions)
            log.info("[LivingWiki] [2/3] Generating adr.md...");
            String adrContext = buildAdrContext(context.toString());
            String adr = generateSingleFile("adr.md", adrContext, provider);
            if (adr != null && !adr.isBlank()) {
                documentationFiles.put("adr.md", adr);
                log.info("[LivingWiki] ✓ adr.md generated ({} chars)", adr.length());
            }

            // File 3: api-reference.md (API documentation) - ONLY if API content was found
            boolean hasApiContent = apiChunksCount > 0;
            if (hasApiContent) {
                log.info("[LivingWiki] [3/3] Generating api-reference.md ({} API chunks found)...", apiChunksCount);
                String apiContext = buildApiContext(context.toString(), semanticContext.toString());
                String apiRef = generateSingleFile("api-reference.md", apiContext, provider);
                if (apiRef != null && !apiRef.isBlank()) {
                    documentationFiles.put("api-reference.md", apiRef);
                    log.info("[LivingWiki] ✓ api-reference.md generated ({} chars)", apiRef.length());
                }
            } else {
                log.info("[LivingWiki] [3/3] Skipping api-reference.md (no API content detected)");
            }

            // Valid to have 1-3 files depending on project type
            if (documentationFiles.size() >= 1) {
                log.info("[LivingWiki] Successfully generated {} documentation files", documentationFiles.size());
                return documentationFiles;
            }

            log.warn("[LivingWiki] No files generated, using fallback");

        } catch (Exception e) {
            log.error("[LivingWiki] Failed to generate documentation files: {}", e.getMessage(), e);
        }

        // Static fallback
        log.info("[LivingWiki] Using static fallback for repo={}", repoUrl);
        return Map.of("README.md", generateFallbackWiki(repoUrl, moduleFiles, allFiles.size(), deps.size()));
    }

    /**
     * Build focused context for README.md generation.
     * Includes: repository structure, general code chunks (NOT API-specific)
     */
    private String buildReadmeContext(String structureContext, String semanticContext) {
        StringBuilder readmeCtx = new StringBuilder();
        readmeCtx.append(structureContext); // Repository structure, file counts

        // Extract ONLY general code chunks (NOT the "API ROUTES & ENDPOINTS" section)
        if (semanticContext != null && semanticContext.contains("═══ API ROUTES & ENDPOINTS ═══")) {
            String generalChunks = semanticContext.substring(0,
                    semanticContext.indexOf("═══ API ROUTES & ENDPOINTS ═══"));
            readmeCtx.append("\n\n─── Representative Code Samples ───\n");
            readmeCtx.append(generalChunks);
        } else {
            readmeCtx.append("\n\n─── Representative Code Samples ───\n");
            readmeCtx.append(semanticContext != null ? semanticContext : "");
        }

        return readmeCtx.toString();
    }

    /**
     * Build focused context for ADR.md generation.
     * Includes: GitHub commits, PRs, issues (architecture decisions)
     */
    private String buildAdrContext(String structureContext) {
        // Extract only the GitHub Architecture Decision Records section
        if (structureContext.contains("─── GitHub Architecture Decision Records ───")) {
            int startIdx = structureContext.indexOf("─── GitHub Architecture Decision Records ───");
            String githubSection = structureContext.substring(startIdx);

            // Take only repo URL and GitHub data (not file lists)
            String repoUrl = structureContext.substring(
                    structureContext.indexOf("Repository: "),
                    structureContext.indexOf("\n", structureContext.indexOf("Repository: ")));

            return repoUrl + "\n\n" + githubSection;
        }

        // Minimal context if no GitHub data
        return structureContext.substring(0, Math.min(1000, structureContext.length()));
    }

    /**
     * Build focused context for api-reference.md generation.
     * Includes: API route chunks ONLY
     */
    private String buildApiContext(String structureContext, String semanticContext) {
        StringBuilder apiCtx = new StringBuilder();

        // Add minimal repo info
        if (structureContext.contains("Repository: ")) {
            String repoUrl = structureContext.substring(
                    structureContext.indexOf("Repository: "),
                    structureContext.indexOf("\n", structureContext.indexOf("Repository: ")));
            apiCtx.append(repoUrl).append("\n\n");
        }

        // Extract ONLY the "API ROUTES & ENDPOINTS" section
        if (semanticContext != null && semanticContext.contains("═══ API ROUTES & ENDPOINTS ═══")) {
            String apiChunks = semanticContext.substring(semanticContext.indexOf("═══ API ROUTES & ENDPOINTS ═══"));
            apiCtx.append(apiChunks);
        } else {
            // Fallback: use all semantic context if no API section found
            apiCtx.append("─── Code Analysis ───\n");
            apiCtx.append(semanticContext != null ? semanticContext : "No code chunks available");
        }

        return apiCtx.toString();
    }

    /**
     * Generate a single documentation file with focused context.
     * For large files (README, API Reference), splits into batches to avoid token
     * limits.
     * Returns the file content as a plain string (not wrapped in delimiters).
     */
    private String generateSingleFile(String fileName, String focusedContext, Map<String, Object> provider) {
        log.info("[LivingWiki] Generating {} with context size: {} chars", fileName, focusedContext.length());

        // Special handling for large files - split into batches
        if (focusedContext.length() > 20000) {
            if ("api-reference.md".equals(fileName)) {
                return generateApiReferenceBatched(focusedContext, provider);
            } else if ("README.md".equals(fileName)) {
                return generateReadmeBatched(focusedContext, provider);
            }
        }

        try {
            if (provider != null) {
                String response = callAiForSingleFile(fileName, focusedContext, provider);
                if (response != null && !response.isBlank()) {
                    return cleanSingleFileResponse(response);
                }
            }

            // Fallback to Gemini
            if (geminiApiKey != null && !geminiApiKey.isBlank()) {
                String response = callGeminiForSingleFile(fileName, focusedContext);
                if (response != null && !response.isBlank()) {
                    return cleanSingleFileResponse(response);
                }
            }
        } catch (Exception e) {
            log.warn("[LivingWiki] Failed to generate {}: {}", fileName, e.getMessage());
        }

        return null;
    }

    /**
     * Generate API Reference with two-stage approach:
     * Stage 1: Extract endpoints into structured data and clean/deduplicate
     * Stage 2: Generate markdown from cleaned data
     * 
     * This ensures consistency and removes redundancy before final formatting.
     */
    private String generateApiReferenceBatched(String apiContext, Map<String, Object> provider) {
        log.info("[LivingWiki] API context too large ({}chars), using two-stage approach", apiContext.length());

        try {
            // Extract repository URL
            String repoUrl = "";
            if (apiContext.contains("Repository: ")) {
                int start = apiContext.indexOf("Repository: ");
                int end = apiContext.indexOf("\n", start);
                if (end > start) {
                    repoUrl = apiContext.substring(start, end);
                }
            }

            // Extract API chunks section
            String apiChunksSection = "";
            if (apiContext.contains("═══ API ROUTES & ENDPOINTS ═══")) {
                apiChunksSection = apiContext.substring(apiContext.indexOf("═══ API ROUTES & ENDPOINTS ═══"));
            }

            // Split into individual code chunks
            List<String> chunks = splitIntoCodeChunks(apiChunksSection);
            log.info("[LivingWiki] Found {} API code chunks to process", chunks.size());

            // Guard: Return null immediately if no chunks found
            if (chunks.isEmpty()) {
                log.warn("[LivingWiki] No API code chunks found after splitting, skipping API reference generation");
                return null;
            }

            // ═══════════════════════════════════════════════════════════════════════════
            // STAGE 1: EXTRACT ENDPOINTS INTO STRUCTURED DATA
            // ═══════════════════════════════════════════════════════════════════════════

            log.info("[LivingWiki] [Stage 1] Extracting and cleaning endpoints from {} chunks", chunks.size());

            // Calculate batch size for extraction (reduced to 5KB to stay under rate
            // limits)
            // 5000 chars ≈ 1250 tokens input + 1500 tokens output = ~2750 total
            // This ensures we stay well under the 6000 TPM limit even with recent usage
            int maxCharsPerBatch = 5000;
            List<List<String>> batches = createBatches(chunks, maxCharsPerBatch);
            log.info("[LivingWiki] Created {} batches for endpoint extraction", batches.size());

            // Wait 60s before starting to ensure sliding window is clear from previous API
            // calls
            if (batches.size() > 0) {
                log.info("[LivingWiki] Waiting 60s to clear rate limit sliding window before starting...");
                try {
                    Thread.sleep(60000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            // Extract endpoints from each batch
            List<ExtractedEndpoint> allEndpoints = new ArrayList<>();

            for (int i = 0; i < batches.size(); i++) {
                log.info("[LivingWiki] [Stage 1] Extracting from batch {}/{}", i + 1, batches.size());

                String batchContext = String.join("\n", batches.get(i));

                // Use DataCleaningService to extract structured endpoints
                List<ExtractedEndpoint> batchEndpoints = dataCleaningService.extractEndpointsFromChunks(
                        batchContext, provider);

                if (!batchEndpoints.isEmpty()) {
                    allEndpoints.addAll(batchEndpoints);
                    log.info("[LivingWiki] ✓ Extracted {} endpoints from batch {}/{}",
                            batchEndpoints.size(), i + 1, batches.size());
                } else {
                    log.warn("[LivingWiki] ✗ No endpoints extracted from batch {}/{}", i + 1, batches.size());
                }

                // Delay between batches to respect rate limits
                if (i < batches.size() - 1) {
                    try {
                        log.info("[LivingWiki] Waiting 60s before next extraction batch (sliding window)...");
                        Thread.sleep(60000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }

            log.info("[LivingWiki] [Stage 1] Extracted total of {} endpoints (before cleaning)", allEndpoints.size());

            // Clean and deduplicate endpoints
            List<ExtractedEndpoint> cleanedEndpoints = dataCleaningService.cleanAndDeduplicateEndpoints(allEndpoints);
            log.info("[LivingWiki] [Stage 1] Cleaned to {} unique endpoints", cleanedEndpoints.size());

            // Guard: Return null if no endpoints found after cleaning
            if (cleanedEndpoints.isEmpty()) {
                log.warn("[LivingWiki] No clean endpoints found after extraction and deduplication, skipping API reference");
                return null;
            }

            // ═══════════════════════════════════════════════════════════════════════════
            // STAGE 2: GENERATE PROFESSIONAL MARKDOWN FROM CLEANED DATA
            // ═══════════════════════════════════════════════════════════════════════════

            log.info("[LivingWiki] [Stage 2] Generating professional API documentation from {} cleaned endpoints",
                    cleanedEndpoints.size());

            // Extract API name from repo URL
            String apiName = "API";
            if (repoUrl != null && !repoUrl.isBlank()) {
                String[] parts = repoUrl.split("/");
                if (parts.length > 0) {
                    apiName = parts[parts.length - 1].replace(".git", "").replace("-", " ");
                }
            }

            // Use DocumentFormattingService for professional output
            String finalDoc = documentFormattingService.formatApiReference(cleanedEndpoints, apiName);

            log.info("[LivingWiki] ✓ Professional API reference generated ({} chars, {} endpoints)",
                    finalDoc.length(), cleanedEndpoints.size());
            return finalDoc;

        } catch (Exception e) {
            log.error("[LivingWiki] Failed to generate batched API reference: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Generate README.md in batches to avoid token limits.
     * Splits code chunks into smaller batches, generates each separately, then
     * combines.
     */
    private String generateReadmeBatched(String readmeContext, Map<String, Object> provider) {
        log.info("[LivingWiki] README context too large ({}chars), splitting into batches", readmeContext.length());

        try {
            // Extract repository structure (small part)
            String repoStructure = "";
            if (readmeContext.contains("Repository: ")) {
                int end = readmeContext.indexOf("─── Representative Code Samples ───");
                if (end > 0) {
                    repoStructure = readmeContext.substring(0, end).trim();
                } else {
                    // Fallback: take first 2000 chars
                    repoStructure = readmeContext.substring(0, Math.min(2000, readmeContext.length()));
                }
            }

            // Extract code samples section
            String codeSamplesSection = "";
            if (readmeContext.contains("─── Representative Code Samples ───")) {
                codeSamplesSection = readmeContext.substring(
                        readmeContext.indexOf("─── Representative Code Samples ───"));
            }

            // Split into individual code chunks
            List<String> chunks = splitIntoCodeChunks(codeSamplesSection);
            log.info("[LivingWiki] Found {} code chunks for README", chunks.size());

            if (chunks.isEmpty()) {
                // If no chunks, try direct generation with truncated context
                String truncated = readmeContext.substring(0, Math.min(15000, readmeContext.length()));
                return generateSingleFileDirect("README.md", truncated, provider);
            }

            // Calculate batch size (reduced to 5KB to stay under rate limits)
            // 5000 chars ≈ 1250 tokens input + 1500 tokens output = ~2750 total
            int maxCharsPerBatch = 5000;
            List<List<String>> batches = createBatches(chunks, maxCharsPerBatch);
            log.info("[LivingWiki] Created {} batches for README generation", batches.size());

            // For README, we'll generate sections and combine
            StringBuilder combinedReadme = new StringBuilder();

            // Generate header with repository structure (always first)
            String headerBatch = repoStructure.length() > 15000
                    ? repoStructure.substring(0, 15000)
                    : repoStructure;
            String header = generateReadmeHeader(headerBatch, provider);
            if (header != null && !header.isBlank()) {
                combinedReadme.append(header).append("\n\n");
                log.info("[LivingWiki] ✓ README header generated");
            }

            // Generate tech stack analysis from code chunks
            for (int i = 0; i < Math.min(batches.size(), 2); i++) { // Max 2 batches for tech details
                log.info("[LivingWiki] Processing README batch {}/{} ({} chunks)",
                        i + 1, batches.size(), batches.get(i).size());

                String batchContext = "Repository Structure:\n" + repoStructure + "\n\n" +
                        "Code Samples (Batch " + (i + 1) + "):\n" +
                        String.join("\n", batches.get(i));

                String batchResult = generateReadmeBatchContent(batchContext, provider, i + 1);

                if (batchResult != null && !batchResult.isBlank()) {
                    combinedReadme.append(batchResult).append("\n\n");
                    log.info("[LivingWiki] ✓ README batch {}/{} processed successfully", i + 1, batches.size());
                } else {
                    log.warn("[LivingWiki] ✗ README batch {}/{} failed, skipping", i + 1, batches.size());
                }

                // Delay between batches
                if (i < Math.min(batches.size(), 2) - 1) {
                    try {
                        log.info(
                                "[LivingWiki] Waiting 60s before next batch to respect rate limits (sliding window)...");
                        Thread.sleep(60000); // 60 seconds for full TPM window reset
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }

            String finalDoc = combinedReadme.toString();
            log.info("[LivingWiki] ✓ Combined README generated ({} chars)", finalDoc.length());
            return finalDoc;

        } catch (Exception e) {
            log.error("[LivingWiki] Failed to generate batched README: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Generate README header with title, description, and main sections.
     */
    private String generateReadmeHeader(String repoStructure, Map<String, Object> provider) {
        String prompt = """
                Generate a comprehensive README.md header for this repository.

                Include these sections:

                # Project Title
                - Descriptive title (extract from repo structure)
                - Brief 1-2 sentence description

                ## Table of Contents
                - Links to main sections

                ## Features
                - Key features (infer from structure)

                ## Project Structure
                - Main directories and their purpose (USE ONLY the structure provided)

                Repository Info:
                %s

                Output markdown (no delimiters). Focus on structure overview.
                """.formatted(repoStructure);

        try {
            if (provider != null) {
                return cleanBatchResponse(callAiForBatch(prompt, provider));
            }
            if (geminiApiKey != null && !geminiApiKey.isBlank()) {
                return cleanBatchResponse(callGeminiForBatch(prompt));
            }
        } catch (Exception e) {
            log.warn("[LivingWiki] README header generation failed: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Generate additional README content from code batch.
     */
    private String generateReadmeBatchContent(String batchContext, Map<String, Object> provider, int batchNum) {
        String prompt = """
                Extract technical details from this code (batch %d for README.md).

                **CRITICAL: Extract ONLY from code provided. DO NOT hallucinate.**

                Generate these sections:

                ## Tech Stack
                - Identify from imports/decorators:
                  * Python: `from flask import` → Flask
                  * Java: `import org.springframework` → Spring Boot
                  * JavaScript: `import express` → Express

                ## Installation
                - Prerequisites (based on detected tech)
                - Installation steps

                ## Usage
                - Basic usage examples (if evident from code)

                Code Analysis:
                %s

                Output markdown (no delimiters). Extract factual information only.
                """.formatted(batchNum, batchContext);

        // Retry logic for rate limit errors
        int maxRetries = 3;
        int retryDelay = 30000; // Start with 30 seconds

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                if (provider != null) {
                    String response = callAiForBatch(prompt, provider);
                    if (response != null && !response.isBlank()) {
                        return cleanBatchResponse(response);
                    }
                }
                if (geminiApiKey != null && !geminiApiKey.isBlank()) {
                    String response = callGeminiForBatch(prompt);
                    if (response != null && !response.isBlank()) {
                        return cleanBatchResponse(response);
                    }
                }
            } catch (Exception e) {
                String errorMsg = e.getMessage();
                boolean isRateLimit = errorMsg != null && errorMsg.contains("429");

                if (isRateLimit && attempt < maxRetries) {
                    log.warn("[LivingWiki] README batch {} rate limited (attempt {}/{}). Retrying in {}s...",
                            batchNum, attempt, maxRetries, retryDelay / 1000);
                    try {
                        Thread.sleep(retryDelay);
                        retryDelay *= 2; // Exponential backoff
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                } else {
                    log.warn("[LivingWiki] README batch {} failed: {}", batchNum, e.getMessage());
                    break;
                }
            }
        }
        return null;
    }

    /**
     * Split code chunks string into individual chunks.
     */
    private List<String> splitIntoCodeChunks(String apiChunksSection) {
        List<String> chunks = new ArrayList<>();
        if (apiChunksSection == null || apiChunksSection.isBlank()) {
            return chunks;
        }

        // Split by chunk markers: "// filepath (chunk N)"
        String[] parts = apiChunksSection.split("\n// ");
        for (String part : parts) {
            if (!part.trim().isEmpty() && !part.contains("═══ API ROUTES")) {
                chunks.add("// " + part.trim());
            }
        }

        return chunks;
    }

    /**
     * Create batches from chunks, ensuring no batch exceeds maxCharsPerBatch.
     */
    private List<List<String>> createBatches(List<String> chunks, int maxCharsPerBatch) {
        List<List<String>> batches = new ArrayList<>();
        List<String> currentBatch = new ArrayList<>();
        int currentBatchSize = 0;

        for (String chunk : chunks) {
            int chunkSize = chunk.length();

            if (currentBatchSize + chunkSize > maxCharsPerBatch && !currentBatch.isEmpty()) {
                // Current batch is full, start a new one
                batches.add(new ArrayList<>(currentBatch));
                currentBatch.clear();
                currentBatchSize = 0;
            }

            currentBatch.add(chunk);
            currentBatchSize += chunkSize;
        }

        // Add remaining batch
        if (!currentBatch.isEmpty()) {
            batches.add(currentBatch);
        }

        return batches;
    }

    /**
     * Generate documentation for a single batch of API endpoints with retry logic.
     */
    private String generateApiBatchDocumentation(String batchContext, Map<String, Object> provider,
            int batchNum, int totalBatches) {
        String prompt = buildApiBatchPrompt(batchContext, batchNum, totalBatches);

        // Retry logic for rate limit errors
        int maxRetries = 3;
        int retryDelay = 30000; // Start with 30 seconds

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                if (provider != null) {
                    String response = callAiForBatch(prompt, provider);
                    if (response != null && !response.isBlank()) {
                        return cleanBatchResponse(response);
                    }
                }

                // Fallback to Gemini
                if (geminiApiKey != null && !geminiApiKey.isBlank()) {
                    String response = callGeminiForBatch(prompt);
                    if (response != null && !response.isBlank()) {
                        return cleanBatchResponse(response);
                    }
                }
            } catch (Exception e) {
                String errorMsg = e.getMessage();
                boolean isRateLimit = errorMsg != null && errorMsg.contains("429");

                if (isRateLimit && attempt < maxRetries) {
                    log.warn("[LivingWiki] Batch {}/{} rate limited (attempt {}/{}). Retrying in {}s...",
                            batchNum, totalBatches, attempt, maxRetries, retryDelay / 1000);
                    try {
                        Thread.sleep(retryDelay);
                        retryDelay *= 2; // Exponential backoff
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                } else {
                    log.warn("[LivingWiki] Batch {}/{} failed: {}", batchNum, totalBatches, e.getMessage());
                    break;
                }
            }
        }

        return null;
    }

    /**
     * Build prompt for a batch of API endpoints.
     */
    private String buildApiBatchPrompt(String batchContext, int batchNum, int totalBatches) {
        return """
                You are documenting API endpoints (batch %d of %d).

                **CRITICAL INSTRUCTIONS - READ CAREFULLY:**

                1. **Router Prefix Detection (MOST IMPORTANT):**
                   - Look for router prefix declarations: `APIRouter(prefix="/auth")`, `Blueprint(url_prefix="/api")`, `@RequestMapping("/users")`
                   - Look for router registration: `app.include_router(router, prefix="/auth")`, `app.register_blueprint(bp, url_prefix="/api")`
                   - COMBINE router prefix + endpoint path to form complete URL
                   - Example: If router has `prefix="/auth"` and endpoint is `@router.post("/login")`, final path is `/auth/login` NOT `/login`

                2. **Extract Real Endpoints:**
                   - Use EXACT paths from code. DO NOT invent or hallucinate endpoints.
                   - Parse decorators: `@app.get("/users")`, `@router.post("/login")`, `@DeleteMapping("/posts/{id}")`
                   - Include path parameters: `{id}`, `{username}`, `{post_id}` exactly as shown

                3. **HTTP Methods:**
                   - GET requests: DO NOT include request body
                   - POST/PUT/PATCH: Include request body if you see it in code

                For EACH endpoint in this batch:

                ### [HTTP METHOD] [COMPLETE PATH WITH PREFIX]

                **Description:** What this endpoint does

                **Authentication:** Yes/No (if you see auth decorators/middleware)

                **Parameters:**
                | Name | Type | Location | Required | Description |
                |------|------|----------|----------|-------------|
                | ... | ... | Path/Query/Header | ... | ... |

                **Request Body:** (if POST/PUT/PATCH)
                ```json
                {
                  "field": "value"
                }
                ```

                **Response (200):**
                ```json
                {
                  "result": "success"
                }
                ```

                **Errors:** 400 Bad Request, 401 Unauthorized, 404 Not Found

                ---

                **Code to analyze:**
                %s

                Output ONLY endpoint documentation (no headers, no "# API Reference" title).
                """
                .formatted(batchNum, totalBatches, batchContext);
    }

    /**
     * Call AI provider for batch processing (smaller context).
     */
    private String callAiForBatch(String prompt, Map<String, Object> provider) {
        String providerName = (String) provider.get("name");
        String apiKey = (String) provider.get("apiKey");
        String baseUrl = (String) provider.get("baseUrl");
        String model = (String) provider.get("model");

        if ("Groq".equals(providerName) || "OpenAI".equals(providerName) ||
                "XAI".equals(providerName) || "LMStudio".equals(providerName)) {

            String url = switch (providerName) {
                case "Groq" -> "https://api.groq.com/openai/v1/chat/completions";
                case "XAI" -> "https://api.x.ai/v1/chat/completions";
                case "OpenAI" -> "https://api.openai.com/v1/chat/completions";
                default -> (baseUrl != null ? baseUrl : "http://localhost:1234") + "/v1/chat/completions";
            };

            Map<String, Object> request = new LinkedHashMap<>();
            request.put("model", model != null && !model.isBlank() ? model : "llama3");
            request.put("messages", List.of(Map.of("role", "user", "content", prompt)));
            request.put("max_tokens", 2000); // Smaller for batches
            request.put("temperature", 0.3);

            HttpHeaders headers = new HttpHeaders();
            if (apiKey != null && !apiKey.isBlank())
                headers.setBearerAuth(apiKey);
            headers.setContentType(MediaType.APPLICATION_JSON);

            return extractReply(
                    restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(request, headers), Map.class),
                    providerName);
        }

        if ("Google".equals(providerName) || "Gemini".equals(providerName)) {
            return callGeminiForBatch(prompt);
        }

        throw new RuntimeException("Unsupported provider for batch: " + providerName);
    }

    /**
     * Call Gemini for batch processing.
     */
    @SuppressWarnings("unchecked")
    private String callGeminiForBatch(String prompt) {
        String url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key="
                + geminiApiKey;
        Map<String, Object> body = Map.of(
                "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.POST,
                new HttpEntity<>(body, headers), Map.class);

        var candidates = (List<Map<String, Object>>) response.getBody().get("candidates");
        var parts = (List<Map<String, Object>>) ((Map<String, Object>) candidates.get(0).get("content"))
                .get("parts");
        return (String) parts.get(0).get("text");
    }

    /**
     * Clean batch response (remove any extra formatting).
     */
    private String cleanBatchResponse(String response) {
        if (response == null)
            return "";

        // Remove any markdown wrappers
        response = response.replaceAll("^```[a-z]*\\s*", "");
        response = response.replaceAll("```\\s*$", "");

        // Remove "# API Reference" or similar headers if present
        response = response.replaceAll("(?m)^#\\s*API\\s*Reference\\s*$", "");
        response = response.replaceAll("(?m)^##\\s*Endpoints\\s*$", "");

        return response.trim();
    }

    /**
     * Direct file generation (fallback for non-batched).
     */
    private String generateSingleFileDirect(String fileName, String context, Map<String, Object> provider) {
        try {
            if (provider != null) {
                String response = callAiForSingleFile(fileName, context, provider);
                if (response != null && !response.isBlank()) {
                    return cleanSingleFileResponse(response);
                }
            }

            if (geminiApiKey != null && !geminiApiKey.isBlank()) {
                String response = callGeminiForSingleFile(fileName, context);
                if (response != null && !response.isBlank()) {
                    return cleanSingleFileResponse(response);
                }
            }
        } catch (Exception e) {
            log.warn("[LivingWiki] Direct generation failed for {}: {}", fileName, e.getMessage());
        }
        return null;
    }

    /**
     * Remove any ===FILE:=== delimiters or markdown code blocks from single file
     * response.
     */
    private String cleanSingleFileResponse(String response) {
        if (response == null)
            return "";

        // Remove ===FILE: filename=== delimiter if present
        response = response.replaceAll("===FILE:\\s*[^=]+===\\s*", "");

        // Remove markdown code block wrappers if present
        if (response.trim().startsWith("```")) {
            response = response.replaceFirst("^```[a-z]*\\s*", "");
            response = response.replaceFirst("```\\s*$", "");
        }

        return response.trim();
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

                1. README.md — Comprehensive project documentation with the following REQUIRED sections:

                   **🚨 CRITICAL INSTRUCTIONS FOR README GENERATION 🚨**

                   1. **ANALYZE THE CODE CHUNKS PROVIDED BELOW**
                      - Review the "API ROUTES & ENDPOINTS" section for API understanding
                      - Review general code chunks for tech stack identification
                      - Look for package.json, pom.xml, requirements.txt, go.mod for dependencies
                      - Look for main entry points (main.py, app.py, index.js, Application.java)
                      - Look for configuration files (config.py, application.properties, .env.example)

                   2. **EXTRACT FACTUAL INFORMATION FROM CODE**
                      - Tech Stack: Identify from import statements, decorators, annotations
                        * Python: `from flask import`, `from fastapi import`, `import django` → Flask/FastAPI/Django
                        * Java: `import org.springframework` → Spring Boot
                        * JavaScript: `import express`, `import React` → Express/React
                      - Features: Extract from route definitions and their descriptions
                        * If you see `/auth/login` and `/auth/register` → Authentication system
                        * If you see `/posts` and `/timeline` → Social media posting
                        * If you see `/inbox` and `/outbox` → Federation support

                   3. **DO NOT HALLUCINATE**
                      - ONLY document technologies you can CONFIRM from code chunks
                      - ONLY describe features you can SEE in the route/controller definitions
                      - If you don't see database code, don't claim "Uses PostgreSQL"
                      - If you don't see Redis imports, don't claim "Caching with Redis"

                   4. **PROJECT STRUCTURE**
                      - Use the "Module Structure" section provided below
                      - DO NOT invent folders that are not listed

                   **README.md Structure:**

                   # Project Title
                   - Clear, descriptive title as H1 header
                   - 1-2 sentence description of what the project does, its purpose, and problem it solves

                   ## Table of Contents
                   - Anchor links to all major sections for quick navigation

                   ## Features
                   - Bulleted list of key features and functionality (EXTRACT from API endpoints)
                   - Highlight what makes this project stand out

                   ## Tech Stack
                   - List technologies with versions (EXTRACT from code imports/decorators)
                   - Include frameworks, libraries, and tools used
                   - Example: If you see `@app.route` → Flask; `@app.get` → FastAPI; `@RequestMapping` → Spring Boot

                   ## Installation and Setup
                   ### Prerequisites
                   - Required software, libraries, OS versions (INFER from tech stack)
                   - System requirements

                   ### Installation Steps
                   - Step-by-step commands to clone repo
                   - Dependency installation commands (match to detected tech stack)

                   ### Configuration
                   - Environment variables needed (look for env variable usage in code chunks)
                   - Configuration files to set up
                   - Database setup if applicable

                   ## Usage Examples
                   - Clear instructions with code snippets
                   - Command-line examples (match to tech stack)
                   - Expected output descriptions

                   ## Project Structure
                   - Brief overview of main directories and their purpose
                   - ASCII tree of key folders (USE ONLY folders from "Module Structure" section below)

                   ## Contributing
                   - Guidelines for bug reports
                   - Pull request process
                   - Coding standards

                   ## License
                   - Clear license statement (e.g., MIT, Apache 2.0)

                   ## Credits and Acknowledgments
                   - Contributors and team members
                   - Third-party libraries and resources used

                   ## Support
                   - How to get help (issue tracker, discussions)
                   - Contact information if applicable

                   ## Project Status
                   - Current development stage (active, maintenance, etc.)
                   - Roadmap of planned features (if applicable)

                   CRITICAL: Base ALL content on the actual repository structure and code chunks provided below. DO NOT invent features, dependencies, or technologies that aren't evident in the code. If information is missing, state "To be documented" rather than hallucinating.

                2. adr.md — Architecture Decision Records (ADRs)

                   **CRITICAL: Use the GitHub Architecture Decision Records section below (if provided) to create factual ADRs based on actual commits, pull requests, and issues.**

                   Document at least 5-10 key architectural decisions using this format for EACH decision:

                   ### ADR-###: [Decision Title]

                   **Status:** Accepted | Proposed | Deprecated | Superseded

                   **Context:**
                   - What is the situation/problem requiring a decision?
                   - What constraints exist (technical, business, time)?
                   - When was this decision made? (Use GitHub commit/PR dates if available)
                   - Who proposed it? (Use GitHub authors if available)

                   **Decision:**
                   - What was decided? Be specific and concrete.
                   - What alternatives were considered?
                   - Why was this chosen over alternatives?

                   **Consequences:**
                   - **Positive:**
                     - Benefits gained
                     - Non-functional requirements addressed (security, performance, scalability)
                   - **Negative:**
                     - Trade-offs accepted
                     - Technical debt incurred
                     - Maintenance overhead
                   - **Risks:**
                     - Potential future issues
                     - Migration challenges

                   **References:**
                   - Link to GitHub commits, PRs, or issues if mentioned in the GitHub ADR section
                   - Link to documentation or RFCs

                   ---

                   **When to create an ADR (include these if evident in GitHub history):**
                   1. Multiple technical options were considered
                   2. Decision impacts future development (breaking changes, migrations)
                   3. Decision affects non-functional requirements (security, performance, scalability)
                   4. Decision affects multiple teams or systems
                   5. Framework/library choice or version upgrade
                   6. Database schema changes or migration
                   7. API design or breaking changes
                   8. Architecture pattern adoption (microservices, event-driven, etc.)

                   **Example ADR Topics to Look For:**
                   - Framework choice (e.g., "Why React over Vue", "Why Spring Boot over Express")
                   - Database decisions (e.g., "Migration from MySQL to PostgreSQL")
                   - Architecture patterns (e.g., "Adopting microservices", "Implementing CQRS")
                   - Security implementations (e.g., "OAuth2 vs JWT authentication")
                   - Performance optimizations (e.g., "Adding Redis cache layer")
                   - Breaking changes (e.g., "API versioning strategy", "Refactoring to REST from GraphQL")

                   IMPORTANT: Base ADRs on actual evidence from:
                   - GitHub commits with keywords like "refactor", "breaking change", "migration", "architecture"
                   - GitHub PRs discussing design decisions
                   - GitHub issues about architectural choices
                   - Code structure patterns visible in the repository

                   If no GitHub data is available, infer decisions from code structure only. DO NOT hallucinate decisions.


                3. api-reference.md — Complete API Reference Documentation

                   **🚨 CRITICAL INSTRUCTIONS FOR API DOCUMENTATION 🚨**

                   1. **ANALYZE THE CODE CHUNKS PROVIDED IN "API ROUTES & ENDPOINTS" SECTION BELOW**
                      - These are actual route definitions, controller methods, and endpoint handlers from the repository
                      - Extract HTTP methods (GET, POST, PUT, DELETE, etc.)
                      - Extract endpoint paths (e.g., /api/users, /auth/login, /posts/{id})
                      - Extract parameter names, types, and locations (path, query, body)
                      - Extract request/response schemas from the code

                   2. **DO NOT HALLUCINATE ENDPOINTS**
                      - ONLY document endpoints that you can SEE in the provided code chunks
                      - If you see: `@app.route("/auth/login", methods=["POST"])` → Document: POST /auth/login
                      - If you see: `router.get("/get_posts")` → Document: GET /get_posts
                      - If you see: `@GetMapping("/users/{username}")` → Document: GET /users/{username}

                   3. **PRESERVE EXACT ENDPOINT PATHS**
                      - Use the EXACT path as written in the code (don't normalize or change it)
                      - If code says `/get_posts`, write `/get_posts` (NOT `/posts`)
                      - If code says `/connect/accept/{connection_id}`, write that EXACTLY

                   4. **EXTRACT DETAILS FROM CODE**
                      - Look for decorators: @app.route, @app.get, @PostMapping, router.post, etc.
                      - Look for function parameters to identify request parameters
                      - Look for request body parsing (request.json, @RequestBody, etc.)
                      - Look for authentication checks (@require_auth, Authentication, etc.)

                   5. **IF INFORMATION IS MISSING**
                      - If you can't determine authentication requirements: state "To be verified"
                      - If you can't see request body schema: provide basic structure or state "To be documented"
                      - DO NOT invent parameters that aren't in the code

                   **Structure Requirements:**

                   # [API Name] API Reference

                   > Brief 1-2 sentence description of the API's purpose and primary use cases.

                   ## Authentication

                   Describe authentication mechanism (Bearer token, API key, OAuth2, etc.):
                   - **Type**: Bearer JWT / API Key / OAuth2
                   - **Header Format**: `Authorization: Bearer <token>`
                   - **How to obtain**: Login endpoint or OAuth flow
                   - **Example**:
                     ```bash
                     curl -H "Authorization: Bearer eyJhbGc..." https://api.example.com/endpoint
                     ```

                   ## Base URL

                   ```
                   Production: https://api.example.com
                   Development: http://localhost:8080/api
                   ```

                   ## Endpoints

                   **For EACH endpoint found in controllers/routes, use this exact format:**

                   ### [HTTP Method] [Endpoint Path]

                   **Description:** Clear 1-2 sentence explanation of what this endpoint does.

                   **HTTP Method:** GET | POST | PUT | PATCH | DELETE

                   **Endpoint URL:** `/api/resource/{id}`

                   **Authentication Required:** Yes | No

                   **Parameters:**

                   | Parameter | Type | Location | Required | Description | Example |
                   |-----------|------|----------|----------|-------------|---------|
                   | id | integer | path | Yes | Unique identifier | 123 |
                   | name | string | query | No | Filter by name | "John" |
                   | data | object | body | Yes | Request payload | See below |

                   **Request Headers:**

                   | Header | Required | Description | Example |
                   |--------|----------|-------------|---------|
                   | Authorization | Yes | Bearer JWT token | Bearer eyJhbGc... |
                   | Content-Type | Yes | Request content type | application/json |

                   **Request Body Example:**

                   ```json
                   {
                     "name": "Example Resource",
                     "type": "sample",
                     "metadata": {
                       "category": "test",
                       "priority": 1
                     }
                   }
                   ```

                   **Request Body Schema:**

                   | Field | Type | Required | Description |
                   |-------|------|----------|-------------|
                   | name | string | Yes | Resource name (max 100 chars) |
                   | type | string | Yes | Resource type (enum: sample, production) |
                   | metadata | object | No | Additional metadata |
                   | metadata.category | string | No | Category tag |
                   | metadata.priority | integer | No | Priority level (1-5) |

                   **Success Response (200 OK):**

                   ```json
                   {
                     "id": 123,
                     "name": "Example Resource",
                     "type": "sample",
                     "created_at": "2024-03-06T10:30:00Z",
                     "updated_at": "2024-03-06T10:30:00Z"
                   }
                   ```

                   **Response Schema:**

                   | Field | Type | Description |
                   |-------|------|-------------|
                   | id | integer | Unique identifier |
                   | name | string | Resource name |
                   | type | string | Resource type |
                   | created_at | string (ISO 8601) | Creation timestamp |
                   | updated_at | string (ISO 8601) | Last update timestamp |

                   **Error Responses:**

                   **400 Bad Request:**
                   ```json
                   {
                     "error": "Validation failed",
                     "message": "Invalid request body",
                     "details": {
                       "field": "name",
                       "issue": "Name cannot be empty"
                     }
                   }
                   ```

                   **401 Unauthorized:**
                   ```json
                   {
                     "error": "Authentication required",
                     "message": "Missing or invalid authorization token"
                   }
                   ```

                   **404 Not Found:**
                   ```json
                   {
                     "error": "Resource not found",
                     "message": "Resource with ID 123 does not exist"
                   }
                   ```

                   **500 Internal Server Error:**
                   ```json
                   {
                     "error": "Internal server error",
                     "message": "An unexpected error occurred"
                   }
                   ```

                   **Code Examples:**

                   **cURL:**
                   ```bash
                   curl -X POST https://api.example.com/api/resource \\
                     -H "Authorization: Bearer YOUR_TOKEN" \\
                     -H "Content-Type: application/json" \\
                     -d '{
                       "name": "Example Resource",
                       "type": "sample"
                     }'
                   ```

                   **Python (requests):**
                   ```python
                   import requests

                   url = "https://api.example.com/api/resource"
                   headers = {
                       "Authorization": "Bearer YOUR_TOKEN",
                       "Content-Type": "application/json"
                   }
                   data = {
                       "name": "Example Resource",
                       "type": "sample"
                   }

                   response = requests.post(url, json=data, headers=headers)
                   print(response.json())
                   ```

                   **JavaScript (Node.js with fetch):**
                   ```javascript
                   const fetch = require('node-fetch');

                   const url = 'https://api.example.com/api/resource';
                   const options = {
                     method: 'POST',
                     headers: {
                       'Authorization': 'Bearer YOUR_TOKEN',
                       'Content-Type': 'application/json'
                     },
                     body: JSON.stringify({
                       name: 'Example Resource',
                       type: 'sample'
                     })
                   };

                   fetch(url, options)
                     .then(res => res.json())
                     .then(data => console.log(data))
                     .catch(err => console.error(err));
                   ```

                   **JavaScript (Axios):**
                   ```javascript
                   const axios = require('axios');

                   const response = await axios.post(
                     'https://api.example.com/api/resource',
                     {
                       name: 'Example Resource',
                       type: 'sample'
                     },
                     {
                       headers: {
                         'Authorization': 'Bearer YOUR_TOKEN',
                         'Content-Type': 'application/json'
                       }
                     }
                   );

                   console.log(response.data);
                   ```

                   ---

                   **CRITICAL ANALYSIS REQUIREMENTS:**

                   To generate accurate API documentation, analyze these files in order of priority:

                   1. **API Specification Files** (PRIMARY SOURCE):
                      - openapi.yaml, swagger.json, api-spec.yaml
                      - These are the definitive source of truth for API contracts

                   2. **Controller/Route Files** (SECONDARY SOURCE):
                      - @RestController, @RequestMapping, @GetMapping, @PostMapping annotations (Spring Boot)
                      - Express routes, FastAPI decorators, Django views
                      - Extract: HTTP method, path, parameters, request/response types

                   3. **Type Definitions/Schemas**:
                      - TypeScript interfaces, Java DTOs, Python dataclasses
                      - Database models, ORM schemas
                      - Extract: field names, types, required/optional, constraints

                   4. **Code Comments/Docstrings**:
                      - JavaDoc, JSDoc, Python docstrings, Swagger annotations
                      - Extract: endpoint descriptions, parameter explanations

                   5. **Test Files**:
                      - API integration tests, request/response examples
                      - Extract: working examples of API usage, edge cases, error handling

                   **Quality Standards:**
                   - ✅ Use actual endpoint paths from code (@RequestMapping values)
                   - ✅ Use actual parameter names from method signatures
                   - ✅ Use actual response types from return statements
                   - ✅ Include ALL endpoints found in controllers (do not skip any)
                   - ✅ Group endpoints by resource/controller
                   - ✅ Consistent formatting across all endpoints
                   - ✅ Include error codes found in exception handlers
                   - ❌ DO NOT hallucinate endpoints that don't exist
                   - ❌ DO NOT invent parameter names or types
                   - ❌ DO NOT create fake example responses

                   If endpoint details are missing from code, state "To be documented" rather than guessing.
                5. documentation-health.md — Health report analyzing coverage, clarity, and consistency. Provide a health score (X out of 100) based on actual code analysis.

                6. api-descriptions.json — Schema-compliant JSON of all endpoints found in controllers/routes.

                7. doc_snapshot.json — Project statistics in JSON format:
                   {
                     "project_stats": {
                       "files": <actual count>,
                       "lines_of_code": <estimate based on file count>,
                       "dependencies": <actual dependency count>
                     },
                     "module_counts": {
                       "module_name": <file count>,
                       ...
                     },
                     "health_tier": "green|yellow|red"
                   }

                8. tree.txt — Professional ASCII tree of the repository structure (use actual structure, do not hallucinate).

                9. tree.json — Structured hierarchical map:
                   {
                     "name": "root",
                     "type": "directory",
                     "children": [...]
                   }

                10. architecture-graph.json — Interactive graph visualization based on actual module relationships:
                    {
                      "nodes": [{"id": "module_name", "label": "Module Name", "type": "module"}],
                      "edges": [{"source": "parent", "target": "child", "label": "contains"}]
                    }

                REQUIREMENTS:
                - Content must be FACTUAL and based on the provided repository structure
                - Avoid placeholders like "Coming soon" - use "To be documented" if info is unavailable
                - For README.md, ensure ALL 10+ sections are included
                - Use professional, industrial-grade terminology
                - For diagrams, use clean, well-aligned ASCII art based on actual structure
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

    // ═══════════════════════════════════════════════════════════════════════
    // SINGLE FILE GENERATION (TO AVOID PAYLOAD TOO LARGE)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Generate a single documentation file using AI provider.
     * Much smaller payload than generating all 3 files at once.
     */
    private String callAiForSingleFile(String fileName, String focusedContext, Map<String, Object> provider) {
        String prompt = buildSingleFilePrompt(fileName, focusedContext);

        String providerName = (String) provider.get("name");
        String apiKey = (String) provider.get("apiKey");
        String baseUrl = (String) provider.get("baseUrl");
        String model = (String) provider.get("model");

        log.info("[LivingWiki] Calling {} for {} (context: {} chars)", providerName, fileName, focusedContext.length());

        if ("Ollama".equals(providerName) || "Groq".equals(providerName) || "XAI".equals(providerName)
                || "OpenAI".equals(providerName) || "LMStudio".equals(providerName)
                || "Anthropic".equals(providerName)) {

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

            String safeModel = (model != null && !model.isBlank()) ? model : "llama3";
            Map<String, Object> request = new java.util.LinkedHashMap<>();
            request.put("model", safeModel);
            request.put("messages", List.of(Map.of("role", "user", "content", prompt)));
            request.put("max_tokens", 4000); // Reduced from 8000 since generating only 1 file
            request.put("temperature", 0.3);

            HttpHeaders headers = new HttpHeaders();
            if (apiKey != null && !apiKey.isBlank())
                headers.setBearerAuth(apiKey);
            headers.setContentType(MediaType.APPLICATION_JSON);

            return extractReply(
                    restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(request, headers), Map.class),
                    providerName);
        }

        if ("Google".equals(providerName) || "Gemini".equals(providerName)) {
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

    /**
     * Generate a single documentation file using Gemini (fallback).
     */
    @SuppressWarnings("unchecked")
    private String callGeminiForSingleFile(String fileName, String focusedContext) {
        String prompt = buildSingleFilePrompt(fileName, focusedContext);

        log.info("[LivingWiki] Calling Gemini for {} (context: {} chars)", fileName, focusedContext.length());

        String url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key="
                + geminiApiKey;
        Map<String, Object> body = Map.of(
                "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers),
                Map.class);

        var candidates = (List<Map<String, Object>>) response.getBody().get("candidates");
        var parts = (List<Map<String, Object>>) ((Map<String, Object>) candidates.get(0).get("content"))
                .get("parts");
        return (String) parts.get(0).get("text");
    }

    /**
     * Build a prompt for generating a single documentation file.
     * Returns simpler, focused prompts to stay within token limits.
     */
    private String buildSingleFilePrompt(String fileName, String focusedContext) {
        if ("README.md".equals(fileName)) {
            return """
                    You are a senior technical documentation engineer. Generate a comprehensive README.md for this repository.

                    **CRITICAL: Extract information from the code provided below. DO NOT hallucinate.**

                    Generate a README.md with these sections:

                    # Project Title
                    - Descriptive title
                    - Brief description (1-2 sentences)

                    ## Features
                    - Key features (extract from API endpoints in code)

                    ## Tech Stack
                    - Extract from imports/decorators:
                      * Python: `from flask import` → Flask, `from fastapi import` → FastAPI
                      * Java: `import org.springframework` → Spring Boot
                      * JavaScript: `import express` → Express

                    ## Installation
                    ### Prerequisites
                    - Based on detected tech stack
                    ### Installation Steps
                    - Clone, dependencies, configuration

                    ## Usage
                    - Basic usage examples

                    ## Project Structure
                    - Main directories (use actual structure from context)

                    ## Contributing, License, Support
                    - Standard sections

                    **Repository Context:**
                    %s

                    Output ONLY the README.md markdown content (no delimiters, no wrappers).
                    """
                    .formatted(focusedContext);

        } else if ("adr.md".equals(fileName)) {
            return """
                    You are a senior technical documentation engineer. Generate Architecture Decision Records (ADRs) for this repository.

                    **CRITICAL: Use ONLY the GitHub commits, PRs, and issues provided below. DO NOT invent decisions.**

                    Generate 5-10 ADRs using this format for EACH:

                    ### ADR-001: [Decision Title]

                    **Status:** Accepted | Proposed | Deprecated

                    **Context:**
                    - Problem situation
                    - Constraints (technical, business, time)
                    - When decided (use GitHub dates)
                    - Who proposed (use GitHub authors)

                    **Decision:**
                    - What was decided
                    - Alternatives considered
                    - Why this choice

                    **Consequences:**
                    - Positive: Benefits, NFR improvements
                    - Negative: Trade-offs, technical debt
                    - Risks: Future issues

                    **References:**
                    - Link to GitHub commits/PRs/issues

                    ---

                    **ADR Topics:**
                    - Framework choices, database decisions, architecture patterns
                    - Security implementations, performance optimizations
                    - Breaking changes, migrations

                    **GitHub Architecture Data:**
                    %s

                    Output ONLY the adr.md markdown content (no delimiters, no wrappers).
                    """
                    .formatted(focusedContext);

        } else if ("api-reference.md".equals(fileName)) {
            return """
                    You are a senior technical documentation engineer. Generate comprehensive API Reference documentation for this repository.

                    **🚨 CRITICAL: Extract endpoints from the code provided below. PRESERVE EXACT PATHS. DO NOT HALLUCINATE. 🚨**

                    **Analysis Instructions:**
                    1. Find route definitions in code: @app.route("/auth/login"), @app.get("/posts"), @PostMapping("/users")
                    2. Extract HTTP method and path EXACTLY as written
                    3. Extract parameters from function signatures
                    4. Document ONLY endpoints you can SEE in code

                    **Structure:**

                    # [API Name] API Reference

                    ## Authentication
                    - Type: Bearer JWT / API Key / OAuth2
                    - How to obtain
                    - Example

                    ## Base URL
                    - Production: https://...
                    - Development: http://localhost:...

                    ## Endpoints

                    For EACH endpoint found in code:

                    ### [HTTP METHOD] [EXACT PATH FROM CODE]

                    **Description:** What this endpoint does

                    **Authentication Required:** Yes/No

                    **Parameters:**
                    | Name | Type | Location | Required | Description | Example |
                    |------|------|----------|----------|-------------|---------|
                    | param1 | string | path | Yes | ... | ... |

                    **Request Body:** (if POST/PUT/PATCH)
                    ```json
                    {
                      "field": "value"
                    }
                    ```

                    **Success Response (200 OK):**
                    ```json
                    {
                      "id": 1,
                      "status": "success"
                    }
                    ```

                    **Error Responses:**
                    - 400 Bad Request: Invalid parameters
                    - 401 Unauthorized: Missing/invalid authentication
                    - 404 Not Found: Resource not found

                    **Example:**
                    ```bash
                    curl -X GET "https://api.example.com/endpoint" \\
                      -H "Authorization: Bearer TOKEN"
                    ```

                    ---

                    **API Routes & Code:**
                    %s

                    Output ONLY the api-reference.md markdown content (no delimiters, no wrappers).
                    """
                    .formatted(focusedContext);
        }

        return "Generate documentation for " + fileName + " based on:\n\n" + focusedContext;
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

                1. README.md — Comprehensive project documentation with these REQUIRED sections:

                   **🚨 ANALYZE CODE CHUNKS BELOW - Extract tech stack from imports/decorators, features from API endpoints. DO NOT HALLUCINATE.**

                   # Project Title
                   - Clear, descriptive title
                   - 1-2 sentence description of purpose and problem solved

                   ## Table of Contents
                   - Anchor links to all major sections

                   ## Features
                   - Bulleted list of key features (EXTRACT from API endpoints in code chunks)

                   ## Tech Stack
                   - Technologies with versions (IDENTIFY from imports: Flask/@app.route, FastAPI/@app.get, Spring/@RequestMapping, Express/router.get)

                   ## Installation and Setup
                   ### Prerequisites
                   - Required software and versions (MATCH to detected tech stack)
                   ### Installation Steps
                   - Step-by-step clone and dependency installation
                   ### Configuration
                   - Environment variables and config files

                   ## Usage Examples
                   - Code snippets and command examples

                   ## Project Structure
                   - Overview of main directories (use actual structure provided, DO NOT hallucinate)

                   ## Contributing
                   - Guidelines for contributions

                   ## License
                   - License statement

                   ## Credits and Acknowledgments
                   - Contributors and third-party libraries

                   ## Support
                   - How to get help

                   ## Project Status
                   - Development stage and roadmap

                   CRITICAL: Base content on actual repository structure and code chunks. DO NOT invent features or technologies. Use "To be documented" if info is missing.

                2. adr.md — Architecture Decision Records
                   **USE GitHub Architecture Decision Records section (if provided) to create factual ADRs from actual commits/PRs/issues.**

                   Format each ADR as:
                   ### ADR-###: [Decision Title]
                   **Status:** Accepted|Proposed|Deprecated
                   **Context:** Problem, constraints, when/who (use GitHub data if available)
                   **Decision:** What was decided, why chosen over alternatives
                   **Consequences:**
                   - Positive: Benefits, NFR improvements (security, performance, scalability)
                   - Negative: Trade-offs, technical debt, maintenance overhead
                   - Risks: Future issues, migration challenges
                   **References:** Link to GitHub commits/PRs/issues if mentioned

                   Include ADRs for: framework choice, database decisions, architecture patterns, security implementations, performance optimizations, breaking changes
                   Base on GitHub commits/PRs/issues with keywords: refactor, breaking change, migration, architecture, decision
                   If no GitHub data, infer from code structure only. DO NOT hallucinate.

                3. api-reference.md — Complete API Reference
                   **🚨 ANALYZE "API ROUTES & ENDPOINTS" CODE CHUNKS - Extract actual endpoints. PRESERVE EXACT PATHS. DO NOT HALLUCINATE.**

                   **CRITICAL:**
                   - Extract endpoints from code: @app.route("/auth/login") → POST /auth/login
                   - Use EXACT paths from code: /get_posts NOT /posts, /connect/accept/{id} NOT /connections/{id}
                   - Extract params from code: def login(username: str, password: str) → Parameters: username, password

                   Structure:
                   # [API Name] API Reference
                   ## Authentication (describe: Bearer JWT, API key, OAuth2, etc.)
                   ## Base URL (production and development URLs)
                   ## Endpoints

                   For EACH endpoint found in code chunks:
                   ### [HTTP Method] [Path]
                   - **Description:** What the endpoint does
                   - **Authentication Required:** Yes/No
                   - **Parameters:** Table with columns: Name | Type | Location | Required | Description | Example
                   - **Request Headers:** Table with columns: Header | Required | Description
                   - **Request Body:** JSON example with actual field names from code
                   - **Request Body Schema:** Table with field details
                   - **Success Response (200):** JSON example with actual response structure
                   - **Response Schema:** Table describing response fields
                   - **Error Responses:** 400, 401, 404, 500 with JSON examples
                   - **Code Examples:** cURL, Python (requests library), JavaScript (Node.js fetch and Axios)

                   CRITICAL Analysis Process:
                   1. Read "API ROUTES & ENDPOINTS" section below carefully
                   2. Extract routes from decorators/annotations: @app.route, @app.get, @PostMapping, router.post
                   3. Extract parameters from function signatures
                   4. Document ONLY what you SEE in code chunks - NO invention
                   5. Test files (working examples of API usage)

                   Quality: Use actual paths, parameters, response types from code. Include ALL endpoints.
                   Group by resource/controller. NO hallucination - use "To be documented" if details missing.

                4. architecture.md — System design based on actual implementation
                5. documentation-health.md — Health score (X out of 100) based on code analysis
                6. api-descriptions.json — Schema-compliant endpoint JSON from actual controllers
                7. doc_snapshot.json — {"project_stats": {"files": <count>, "lines_of_code": <estimate>, "dependencies": <count>}, "module_counts": {...}, "health_tier": "green|yellow|red"}
                8. tree.txt — ASCII tree of actual structure (do not hallucinate)
                9. tree.json — {"name": "root", "type": "directory", "children": [...]}
                10. architecture-graph.json — {"nodes": [...], "edges": [...]} based on actual module relationships

                REQUIREMENTS:
                - Content must be FACTUAL based on provided structure
                - Avoid hallucination - use "To be documented" when uncertain
                - README.md must include ALL sections listed above
                - DO NOT wrap in markdown code blocks

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

        // Extract project name from repo URL
        String projectName = repoUrl.substring(repoUrl.lastIndexOf('/') + 1).replace(".git", "");

        wiki.append("# ").append(projectName).append("\n\n");
        wiki.append("> A comprehensive software project managed and analyzed by MindVex\n\n");

        wiki.append("## Table of Contents\n\n");
        wiki.append("- [Overview](#overview)\n");
        wiki.append("- [Features](#features)\n");
        wiki.append("- [Tech Stack](#tech-stack)\n");
        wiki.append("- [Installation and Setup](#installation-and-setup)\n");
        wiki.append("- [Usage Examples](#usage-examples)\n");
        wiki.append("- [Project Structure](#project-structure)\n");
        wiki.append("- [Contributing](#contributing)\n");
        wiki.append("- [License](#license)\n");
        wiki.append("- [Support](#support)\n");
        wiki.append("- [Project Status](#project-status)\n\n");

        wiki.append("## Overview\n\n");
        wiki.append("**Repository:** ").append(repoUrl).append("\n\n");
        wiki.append("This project contains **").append(totalFiles).append(" files** across **")
                .append(modules.size()).append(" modules** with **")
                .append(totalDeps).append(" dependency relationships**.\n\n");

        wiki.append("## Features\n\n");
        wiki.append("- ✅ Modular architecture with ").append(modules.size()).append(" distinct modules\n");
        wiki.append("- ✅ Comprehensive codebase with ").append(totalFiles).append(" source files\n");
        wiki.append("- ✅ Well-defined dependency relationships (").append(totalDeps).append(" connections)\n");
        wiki.append("- ✅ Managed and analyzed by MindVex intelligent code analysis platform\n\n");

        wiki.append("## Tech Stack\n\n");
        wiki.append(
                "*To be documented* - Please run full analysis to detect technologies and frameworks used in this project.\n\n");

        wiki.append("## Installation and Setup\n\n");
        wiki.append("### Prerequisites\n\n");
        wiki.append(
                "*To be documented* - Prerequisites will be identified after analyzing package managers and build files.\n\n");
        wiki.append("### Installation Steps\n\n");
        wiki.append("```bash\n");
        wiki.append("# Clone the repository\n");
        wiki.append("git clone ").append(repoUrl).append("\n");
        wiki.append("cd ").append(projectName).append("\n\n");
        wiki.append("# Install dependencies\n");
        wiki.append("# (Command will be identified based on project type)\n");
        wiki.append("```\n\n");
        wiki.append("### Configuration\n\n");
        wiki.append(
                "*To be documented* - Configuration details will be extracted from config files during analysis.\n\n");

        wiki.append("## Usage Examples\n\n");
        wiki.append(
                "*To be documented* - Usage examples will be generated based on entry points and API endpoints found in the code.\n\n");

        wiki.append("## Project Structure\n\n");
        wiki.append("The project is organized into the following modules:\n\n");

        for (var entry : modules.entrySet()) {
            wiki.append("### ").append(entry.getKey()).append("\n\n");
            wiki.append("Contains ").append(entry.getValue().size()).append(" files:\n\n");
            entry.getValue().stream().limit(10).forEach(f -> wiki.append("- `").append(f).append("`\n"));
            if (entry.getValue().size() > 10) {
                wiki.append("- *... and ").append(entry.getValue().size() - 10).append(" more*\n");
            }
            wiki.append("\n");
        }

        wiki.append("## Contributing\n\n");
        wiki.append("Contributions are welcome! Please follow these guidelines:\n\n");
        wiki.append("1. Fork the repository\n");
        wiki.append("2. Create a feature branch (`git checkout -b feature/amazing-feature`)\n");
        wiki.append("3. Commit your changes (`git commit -m 'Add amazing feature'`)\n");
        wiki.append("4. Push to the branch (`git push origin feature/amazing-feature`)\n");
        wiki.append("5. Open a Pull Request\n\n");

        wiki.append("## License\n\n");
        wiki.append("*To be documented* - License information will be extracted from LICENSE file if present.\n\n");

        wiki.append("## Credits and Acknowledgments\n\n");
        wiki.append(
                "- **Analysis Platform**: [MindVex](https://github.com/hariPrasathK-Dev/MindVex_Editor_Frontend)\n");
        wiki.append("- **Contributors**: To be documented\n");
        wiki.append("- **Third-party Libraries**: To be identified during dependency analysis\n\n");

        wiki.append("## Support\n\n");
        wiki.append("For questions and support:\n\n");
        wiki.append("- 📝 Create an issue in the [issue tracker](").append(repoUrl.replace(".git", "/issues"))
                .append(")\n");
        wiki.append("- 💬 Check existing discussions for common questions\n\n");

        wiki.append("## Project Status\n\n");
        wiki.append("🔄 **Active Development** - This project is being actively analyzed and documented.\n\n");
        wiki.append("### Roadmap\n\n");
        wiki.append("- [ ] Complete dependency analysis\n");
        wiki.append("- [ ] Generate comprehensive API documentation\n");
        wiki.append("- [ ] Identify and document all tech stack components\n");
        wiki.append("- [ ] Create architecture diagrams\n");
        wiki.append("- [ ] Document configuration and deployment procedures\n\n");
        wiki.append("---\n\n");
        wiki.append(
                "*This documentation was auto-generated by MindVex. For comprehensive documentation, please run a full Living Wiki generation with an AI provider configured.*\n");

        return wiki.toString();
    }

    /**
     * Fetch user's GitHub access token from the database.
     * Returns null if user not found or token not set.
     * 
     * @param userId User ID
     * @return GitHub access token or null
     */
    private String getUserGithubToken(Long userId) {
        return userRepository.findById(userId)
                .map(User::getGithubAccessToken)
                .orElse(null);
    }
}
