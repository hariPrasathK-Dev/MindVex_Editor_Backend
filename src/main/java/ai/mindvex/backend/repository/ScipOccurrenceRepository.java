package ai.mindvex.backend.repository;

import ai.mindvex.backend.entity.ScipOccurrence;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ScipOccurrenceRepository extends JpaRepository<ScipOccurrence, Long> {

    void deleteByDocumentId(Long documentId);

    /**
     * Find occurrences that overlap a given cursor position.
     * Used by the hover provider to look up the symbol at a position.
     */
    @Query("""
            SELECT o FROM ScipOccurrence o
            WHERE o.documentId = :docId
              AND o.startLine <= :line AND o.endLine >= :line
              AND o.startChar <= :character AND o.endChar >= :character
            ORDER BY (o.endLine - o.startLine), (o.endChar - o.startChar)
            """)
    List<ScipOccurrence> findAtPosition(
            @Param("docId") Long documentId,
            @Param("line") int line,
            @Param("character") int character);
}
