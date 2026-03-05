package ai.mindvex.backend.controller;

import ai.mindvex.backend.dto.GraphResponse;
import ai.mindvex.backend.dto.GraphResponse.CyEdge;
import ai.mindvex.backend.dto.GraphResponse.CyEdge.CyEdgeData;
import ai.mindvex.backend.dto.GraphResponse.CyNode;
import ai.mindvex.backend.dto.GraphResponse.CyNode.CyNodeData;
import ai.mindvex.backend.dto.ReferenceResult;
import ai.mindvex.backend.entity.IndexJob;
import ai.mindvex.backend.entity.User;
import ai.mindvex.backend.entity.VectorEmbedding;
import ai.mindvex.backend.repository.IndexJobRepository;
import ai.mindvex.backend.repository.UserRepository;
import ai.mindvex.backend.service.DependencyEngine;
import ai.mindvex.backend.service.EmbeddingIngestionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * GraphController
 *
 * POST /api/graph/build — async: extract file dependency edges from SCIP data
 * GET /api/graph/dependencies — returns Cytoscape.js-compatible graph
 * GET /api/graph/references — SCIP-powered Find All References
 */
@RestController
@RequestMapping("/api/graph")
@RequiredArgsConstructor
@Slf4j
public class GraphController {

    private final DependencyEngine dependencyEngine;
    private final IndexJobRepository indexJobRepository;
    private final UserRepository userRepository;
    private final EmbeddingIngestionService embeddingService;
    private final JdbcTemplate jdbc;

    // ─── POST /api/graph/build ────────────────────────────────────────────────

    /**
     * Enqueue a dependency-graph-build job.
     * The IndexJobWorker will call DependencyEngine.extractEdges() when it picks it
     * up.
     */
    @PostMapping("/build")
    public ResponseEntity<Map<String, Object>> buildGraph(
            @RequestParam String repoUrl,
            Authentication authentication) {

        Long userId = extractUserId(authentication);

        IndexJob job = new IndexJob();
        job.setUserId(userId);
        job.setRepoUrl(repoUrl);
        job.setStatus("pending");
        job.setJobType("graph_build");
        job.setPayload("{}");
        indexJobRepository.save(job);

        log.info("[GraphController] Enqueued graph_build job {} for {}", job.getId(), repoUrl);
        return ResponseEntity.accepted()
                .body(Map.of("jobId", job.getId(), "status", "pending"));
    }

    // ─── GET /api/graph/dependencies ─────────────────────────────────────────

    /**
     * Returns the full dependency graph (or a rooted sub-tree if rootFile is
     * supplied)
     * in Cytoscape.js format: { nodes, edges, cycles }.
     */
    @GetMapping("/dependencies")
    public ResponseEntity<GraphResponse> getDependencies(
            @RequestParam String repoUrl,
            @RequestParam(required = false) String rootFile,
            @RequestParam(defaultValue = "20") int depth,
            Authentication authentication) {

        Long userId = extractUserId(authentication);

        // Collect raw edges
        List<Object[]> rawEdges;
        List<String> cycles = new ArrayList<>();

        if (rootFile != null && !rootFile.isBlank()) {
            Map<String, Object> tree = dependencyEngine.buildTransitiveDeps(userId, repoUrl, rootFile, depth);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> treeEdges = (List<Map<String, Object>>) tree.get("edges");
            @SuppressWarnings("unchecked")
            List<String> treeCycles = (List<String>) tree.get("cycles");
            cycles = treeCycles;

            // Convert map rows to Object[] for uniform processing
            rawEdges = treeEdges.stream()
                    .map(m -> new Object[] {
                            m.get("source"),
                            m.get("target"),
                            "reference",
                            m.get("cycle")
                    })
                    .collect(Collectors.toList());
        } else {
            rawEdges = dependencyEngine.getAllEdgesRaw(userId, repoUrl);
        }

        // Build node set from distinct file paths
        Set<String> filePaths = new LinkedHashSet<>();
        for (Object[] row : rawEdges) {
            filePaths.add((String) row[0]);
            filePaths.add((String) row[1]);
        }

        // Nodes
        List<CyNode> nodes = filePaths.stream()
                .map(path -> new CyNode(new CyNodeData(
                        nodeId(path),
                        basename(path),
                        path,
                        detectLanguage(path))))
                .collect(Collectors.toList());

        // Edges
        AtomicInteger edgeCounter = new AtomicInteger(0);
        List<CyEdge> edges = rawEdges.stream()
                .map(row -> {
                    String source = (String) row[0];
                    String target = (String) row[1];
                    String type = row.length > 2 && row[2] != null ? (String) row[2] : "reference";
                    boolean isCycle = row.length > 3 && row[3] instanceof Boolean b && b;
                    return new CyEdge(new CyEdgeData(
                            "e" + edgeCounter.getAndIncrement(),
                            nodeId(source),
                            nodeId(target),
                            type,
                            isCycle));
                })
                .collect(Collectors.toList());

        log.info("[GraphController] Returning {} nodes, {} edges for {}", nodes.size(), edges.size(), repoUrl);
        return ResponseEntity.ok(new GraphResponse(nodes, edges, cycles));
    }

    // ─── GET /api/graph/references ────────────────────────────────────────────

    /**
     * Returns all occurrences of a SCIP symbol (references + definitions),
     * grouped by file. Powered by the SCIP semantic index — NOT a text grep.
     */
    @GetMapping("/references")
    public ResponseEntity<List<ReferenceResult>> getReferences(
            @RequestParam String repoUrl,
            @RequestParam String symbol,
            Authentication authentication) {

        Long userId = extractUserId(authentication);

        String sql = """
                SELECT d.relative_uri AS filePath,
                       o.start_line, o.start_char, o.end_line, o.end_char,
                       o.symbol, o.role_flags
                FROM code_intelligence.scip_occurrences o
                JOIN code_intelligence.scip_documents d ON d.id = o.document_id
                WHERE d.user_id  = ?
                  AND d.repo_url = ?
                  AND o.symbol   = ?
                ORDER BY d.relative_uri, o.start_line
                """;

        List<ReferenceResult> refs = jdbc.query(
                sql,
                (rs, rowNum) -> new ReferenceResult(
                        rs.getString("filePath"),
                        rs.getInt("start_line"),
                        rs.getInt("start_char"),
                        rs.getInt("end_line"),
                        rs.getInt("end_char"),
                        rs.getString("symbol"),
                        rs.getInt("role_flags")),
                userId, repoUrl, symbol);

        log.info("[GraphController] Found {} references for symbol '{}' in {}", refs.size(), symbol, repoUrl);
        return ResponseEntity.ok(refs);
    }

    // ─── POST /api/graph/semantic-filter ─────────────────────────────────────

    /**
     * Filter graph nodes using semantic search on embeddings.
     * Returns node IDs that match the semantic query.
     */
    @PostMapping("/semantic-filter")
    public ResponseEntity<Map<String, Object>> semanticFilter(
            @RequestParam String repoUrl,
            @RequestBody Map<String, Object> body,
            Authentication authentication) {

        Long userId = extractUserId(authentication);
        String query = (String) body.getOrDefault("query", "");
        int topK = (int) body.getOrDefault("topK", 10);

        log.info("[GraphController] Semantic filter: query='{}', topK={}", query, topK);

        try {
            List<VectorEmbedding> results = embeddingService.semanticSearch(userId, repoUrl, query, topK);

            // Extract unique file paths from matching chunks
            Set<String> matchingFiles = results.stream()
                    .map(VectorEmbedding::getFilePath)
                    .collect(Collectors.toSet());

            // Convert to node IDs for graph filtering
            List<String> nodeIds = matchingFiles.stream()
                    .map(this::nodeId)
                    .collect(Collectors.toList());

            List<Map<String, Object>> details = results.stream()
                    .map(r -> Map.<String, Object>of(
                            "filePath", r.getFilePath(),
                            "nodeId", nodeId(r.getFilePath()),
                            "chunkIndex", r.getChunkIndex(),
                            "preview", r.getChunkText().length() > 200
                                    ? r.getChunkText().substring(0, 200) + "..."
                                    : r.getChunkText()))
                    .collect(Collectors.toList());

            return ResponseEntity.ok(Map.of(
                    "query", query,
                    "matchingNodes", nodeIds,
                    "details", details,
                    "totalMatches", results.size()));

        } catch (Exception e) {
            log.error("[GraphController] Semantic filter failed: {}", e.getMessage(), e);
            return ResponseEntity.ok(Map.of(
                    "query", query,
                    "matchingNodes", List.of(),
                    "details", List.of(),
                    "totalMatches", 0,
                    "error", "Embeddings not available or search failed: " + e.getMessage()));
        }
    }

    // ─── GET /api/graph/stats ────────────────────────────────────────────────

    /**
     * Returns graph statistics including complexity metrics.
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getGraphStats(
            @RequestParam String repoUrl,
            Authentication authentication) {

        Long userId = extractUserId(authentication);

        try {
            // Get basic graph data
            List<Object[]> edges = dependencyEngine.getAllEdgesRaw(userId, repoUrl);
            Set<String> files = new HashSet<>();
            Map<String, Integer> inDegree = new HashMap<>();
            Map<String, Integer> outDegree = new HashMap<>();

            for (Object[] edge : edges) {
                String source = (String) edge[0];
                String target = (String) edge[1];
                files.add(source);
                files.add(target);
                outDegree.put(source, outDegree.getOrDefault(source, 0) + 1);
                inDegree.put(target, inDegree.getOrDefault(target, 0) + 1);
            }

            // Calculate complexity scores based on degree centrality
            Map<String, Map<String, Object>> nodeStats = new HashMap<>();
            for (String file : files) {
                int in = inDegree.getOrDefault(file, 0);
                int out = outDegree.getOrDefault(file, 0);
                int complexity = in + out;

                nodeStats.put(nodeId(file), Map.of(
                        "filePath", file,
                        "inDegree", in,
                        "outDegree", out,
                        "complexity", complexity,
                        "language", detectLanguage(file)));
            }

            // Language distribution
            Map<String, Long> languages = files.stream()
                    .collect(Collectors.groupingBy(
                            this::detectLanguage,
                            Collectors.counting()));

            // Find most connected nodes (hubs)
            List<Map<String, Object>> hubs = nodeStats.values().stream()
                    .sorted((a, b) -> Integer.compare(
                            (Integer) b.get("complexity"),
                            (Integer) a.get("complexity")))
                    .limit(10)
                    .collect(Collectors.toList());

            return ResponseEntity.ok(Map.of(
                    "totalNodes", files.size(),
                    "totalEdges", edges.size(),
                    "languages", languages,
                    "hubs", hubs,
                    "nodeStats", nodeStats,
                    "avgComplexity", nodeStats.values().stream()
                            .mapToInt(n -> (Integer) n.get("complexity"))
                            .average()
                            .orElse(0.0)));

        } catch (Exception e) {
            log.error("[GraphController] Stats failed: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private Long extractUserId(Authentication authentication) {
        UserDetails userDetails = (UserDetails) authentication.getPrincipal();
        User user = userRepository.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new RuntimeException("User not found"));
        return user.getId();
    }

    /** Stable node ID: replace non-alphanumeric with underscore. */
    private String nodeId(String filePath) {
        return filePath.replaceAll("[^a-zA-Z0-9]", "_");
    }

    private String basename(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private String detectLanguage(String path) {
        if (path.endsWith(".java"))
            return "java";
        if (path.endsWith(".kt"))
            return "kotlin";
        if (path.endsWith(".py"))
            return "python";
        if (path.endsWith(".ts") || path.endsWith(".tsx"))
            return "typescript";
        if (path.endsWith(".js") || path.endsWith(".jsx"))
            return "javascript";
        if (path.endsWith(".go"))
            return "go";
        if (path.endsWith(".rs"))
            return "rust";
        if (path.endsWith(".cpp") || path.endsWith(".cc"))
            return "cpp";
        if (path.endsWith(".cs"))
            return "csharp";
        return "unknown";
    }
}
