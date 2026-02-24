package ai.mindvex.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * JPA entity for code_intelligence.vector_embeddings.
 * Stores high-dimensional embeddings for code chunks to enable semantic search.
 */
@Entity
@Table(schema = "code_intelligence", name = "vector_embeddings")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VectorEmbedding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "repo_url", nullable = false, length = 1000)
    private String repoUrl;

    @Column(name = "file_path", nullable = false, length = 2000)
    private String filePath;

    @Column(name = "chunk_index", nullable = false)
    @Builder.Default
    private Integer chunkIndex = 0;

    @Column(name = "chunk_text", nullable = false, columnDefinition = "TEXT")
    private String chunkText;

    /**
     * The embedding vector is stored as a float[] in Java.
     * We use a native query for similarity search since JPA doesn't
     * natively support the pgvector type.
     */
    @Column(name = "embedding", columnDefinition = "TEXT")
    private String embedding; // JPA sees TEXT; actual DB column is vector(768) — native queries handle vector ops

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
