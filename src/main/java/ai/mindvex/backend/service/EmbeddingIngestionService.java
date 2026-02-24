package ai.mindvex.backend.service;

import ai.mindvex.backend.entity.VectorEmbedding;
import ai.mindvex.backend.repository.VectorEmbeddingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Ingests source code files as chunked vector embeddings into PostgreSQL.
 *
 * Workflow:
 * 1. Walk the cloned repo directory for source files
 * 2. Chunk each file into ~50 line segments
 * 3. Call Gemini embedding API to generate 768-dim vectors
 * 4. Store (file_path, chunk_index, chunk_text, embedding) in vector_embeddings table
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmbeddingIngestionService {

    private final VectorEmbeddingRepository embeddingRepo;
    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${gemini.api-key:#{null}}")
    private String geminiApiKey;

    private static final int CHUNK_SIZE_LINES = 50;
    private static final Set<String> SOURCE_EXTENSIONS = Set.of(
            ".ts", ".tsx", ".js", ".jsx", ".py", ".java", ".kt", ".go",
            ".rs", ".cs", ".cpp", ".c", ".h", ".rb", ".swift", ".md"
    );
    private static final Set<String> SKIP_DIRS = Set.of(
            "node_modules", ".git", "dist", "build", "target", "__pycache__", "vendor"
    );

    /**
     * Ingest a cloned repo directory into vector embeddings.
     *
     * @param userId  the owning user
     * @param repoUrl the repo URL (used as key)
     * @param repoDir the local path to the cloned repo
     * @return number of chunks embedded
     */
    @Transactional
    public int ingestRepo(Long userId, String repoUrl, Path repoDir) throws IOException {
        log.info("[EmbeddingIngestion] Starting for user={} repo={}", userId, repoUrl);

        // Clear stale embeddings
        embeddingRepo.deleteByUserIdAndRepoUrl(userId, repoUrl);

        // Collect source files
        List<Path> sourceFiles = new ArrayList<>();
        Files.walkFileTree(repoDir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (SKIP_DIRS.contains(dir.getFileName().toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String name = file.getFileName().toString();
                String ext = name.contains(".") ? name.substring(name.lastIndexOf('.')) : "";
                if (SOURCE_EXTENSIONS.contains(ext.toLowerCase()) && attrs.size() < 500_000) {
                    sourceFiles.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });

        log.info("[EmbeddingIngestion] Found {} source files", sourceFiles.size());

        int totalChunks = 0;
        List<VectorEmbedding> batch = new ArrayList<>();

        for (Path file : sourceFiles) {
            String relativePath = repoDir.relativize(file).toString().replace('\\', '/');
            try {
                String content = Files.readString(file);
                List<String> chunks = chunkCode(content);

                for (int i = 0; i < chunks.size(); i++) {
                    String chunk = chunks.get(i);
                    String embeddingVec = generateEmbedding(chunk);

                    batch.add(VectorEmbedding.builder()
                            .userId(userId)
                            .repoUrl(repoUrl)
                            .filePath(relativePath)
                            .chunkIndex(i)
                            .chunkText(chunk)
                            .embedding(embeddingVec)
                            .build());

                    totalChunks++;

                    // Batch save every 50 chunks
                    if (batch.size() >= 50) {
                        embeddingRepo.saveAll(batch);
                        batch.clear();
                    }
                }
            } catch (Exception e) {
                log.debug("[EmbeddingIngestion] Skipping {}: {}", relativePath, e.getMessage());
            }
        }

        if (!batch.isEmpty()) {
            embeddingRepo.saveAll(batch);
        }

        log.info("[EmbeddingIngestion] Ingested {} chunks for {}", totalChunks, repoUrl);
        return totalChunks;
    }

    /**
     * Search for code chunks semantically similar to a query.
     */
    public List<VectorEmbedding> semanticSearch(Long userId, String repoUrl, String query, int topK) {
        String queryEmbedding = generateEmbedding(query);
        if (queryEmbedding == null) return Collections.emptyList();
        try {
            return embeddingRepo.findSimilar(userId, repoUrl, queryEmbedding, topK);
        } catch (Exception e) {
            log.warn("[SemanticSearch] Vector search failed (pgvector may not be available locally): {}",
                    e.getMessage());
            return Collections.emptyList();
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private List<String> chunkCode(String content) {
        String[] lines = content.split("\n");
        List<String> chunks = new ArrayList<>();

        for (int i = 0; i < lines.length; i += CHUNK_SIZE_LINES) {
            int end = Math.min(i + CHUNK_SIZE_LINES, lines.length);
            String chunk = Arrays.stream(lines, i, end).collect(Collectors.joining("\n"));
            if (!chunk.isBlank()) {
                chunks.add(chunk);
            }
        }

        return chunks;
    }

    /**
     * Generate a 768-dim embedding via Gemini API.
     * Falls back to a deterministic hash-based mock if no API key is configured.
     */
    private String generateEmbedding(String text) {
        if (geminiApiKey != null && !geminiApiKey.isBlank()) {
            try {
                return callGeminiEmbeddingApi(text);
            } catch (Exception e) {
                log.warn("[EmbeddingIngestion] Gemini API failed, using fallback: {}", e.getMessage());
            }
        }
        // Fallback: deterministic mock embedding from text hash
        return generateMockEmbedding(text);
    }

    @SuppressWarnings("unchecked")
    private String callGeminiEmbeddingApi(String text) {
        String url = "https://generativelanguage.googleapis.com/v1beta/models/text-embedding-004:embedContent?key=" + geminiApiKey;

        Map<String, Object> body = Map.of(
                "model", "models/text-embedding-004",
                "content", Map.of("parts", List.of(Map.of("text", text.length() > 2000 ? text.substring(0, 2000) : text)))
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<Map> response = restTemplate.exchange(
                url, HttpMethod.POST, new HttpEntity<>(body, headers), Map.class
        );

        Map<String, Object> embedding = (Map<String, Object>) response.getBody().get("embedding");
        List<Double> values = (List<Double>) embedding.get("values");

        return "[" + values.stream().map(String::valueOf).collect(Collectors.joining(",")) + "]";
    }

    private String generateMockEmbedding(String text) {
        float[] vec = new float[768];
        int hash = text.hashCode();
        Random rng = new Random(hash);
        for (int i = 0; i < 768; i++) {
            vec[i] = (rng.nextFloat() - 0.5f) * 2.0f;
        }
        // Normalize
        float norm = 0;
        for (float v : vec) norm += v * v;
        norm = (float) Math.sqrt(norm);
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 768; i++) {
            if (i > 0) sb.append(",");
            sb.append(String.format("%.6f", vec[i] / norm));
        }
        sb.append("]");
        return sb.toString();
    }
}
