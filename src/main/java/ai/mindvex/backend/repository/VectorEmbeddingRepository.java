package ai.mindvex.backend.repository;

import ai.mindvex.backend.entity.VectorEmbedding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repository for vector_embeddings with native pgvector similarity search.
 */
@Repository
public interface VectorEmbeddingRepository extends JpaRepository<VectorEmbedding, Long> {

    /**
     * Semantic similarity search using cosine distance.
     * Returns the top-K most similar code chunks to the query embedding.
     */
    @Query(value = """
            SELECT * FROM code_intelligence.vector_embeddings
            WHERE user_id = :userId AND repo_url = :repoUrl
            AND embedding IS NOT NULL
            ORDER BY cast(embedding AS vector) <=> cast(:queryEmbedding AS vector)
            LIMIT :topK
            """, nativeQuery = true)
    List<VectorEmbedding> findSimilar(
            @Param("userId") Long userId,
            @Param("repoUrl") String repoUrl,
            @Param("queryEmbedding") String queryEmbedding,
            @Param("topK") int topK
    );

    /**
     * Delete all embeddings for a user+repo (for re-indexing).
     */
    @Modifying
    @Query("DELETE FROM VectorEmbedding v WHERE v.userId = :userId AND v.repoUrl = :repoUrl")
    void deleteByUserIdAndRepoUrl(@Param("userId") Long userId, @Param("repoUrl") String repoUrl);

    long countByUserIdAndRepoUrl(Long userId, String repoUrl);
}
