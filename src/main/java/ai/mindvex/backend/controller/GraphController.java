package ai.mindvex.backend.controller;

import ai.mindvex.backend.dto.GraphResponse;
import ai.mindvex.backend.dto.GraphResponse.CyEdge;
import ai.mindvex.backend.dto.GraphResponse.CyEdge.CyEdgeData;
import ai.mindvex.backend.dto.GraphResponse.CyNode;
import ai.mindvex.backend.dto.GraphResponse.CyNode.CyNodeData;
import ai.mindvex.backend.dto.ReferenceResult;
import ai.mindvex.backend.entity.IndexJob;
import ai.mindvex.backend.repository.IndexJobRepository;
import ai.mindvex.backend.service.DependencyEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
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
            @AuthenticationPrincipal Jwt jwt) {

        Long userId = extractUserId(jwt);

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
            @AuthenticationPrincipal Jwt jwt) {

        Long userId = extractUserId(jwt);

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
            @AuthenticationPrincipal Jwt jwt) {

        Long userId = extractUserId(jwt);

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

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private Long extractUserId(Jwt jwt) {
        return Long.parseLong(jwt.getSubject());
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
