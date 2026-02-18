package ai.mindvex.backend.repository;

import ai.mindvex.backend.entity.ScipDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ScipDocumentRepository extends JpaRepository<ScipDocument, Long> {

    Optional<ScipDocument> findByUserIdAndRepoUrlAndRelativeUri(
            Long userId, String repoUrl, String relativeUri);
}
