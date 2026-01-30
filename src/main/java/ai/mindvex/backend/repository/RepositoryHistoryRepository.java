package ai.mindvex.backend.repository;

import ai.mindvex.backend.entity.RepositoryHistory;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository interface for RepositoryHistory entity.
 * Provides methods for managing user's repository import history.
 */
@Repository
public interface RepositoryHistoryRepository extends JpaRepository<RepositoryHistory, Long> {

    /**
     * Find all repositories for a user, ordered by last accessed date.
     */
    List<RepositoryHistory> findByUserIdOrderByLastAccessedAtDesc(Long userId);

    /**
     * Find repositories for a user with pagination.
     */
    List<RepositoryHistory> findByUserId(Long userId, Pageable pageable);

    /**
     * Find a repository by user ID and URL.
     */
    Optional<RepositoryHistory> findByUserIdAndUrl(Long userId, String url);

    /**
     * Find a repository by ID and user ID (for security check).
     */
    Optional<RepositoryHistory> findByIdAndUserId(Long id, Long userId);

    /**
     * Count repositories for a user.
     */
    long countByUserId(Long userId);

    /**
     * Delete all repositories for a user.
     */
    @Modifying
    @Query("DELETE FROM RepositoryHistory r WHERE r.userId = :userId")
    void deleteAllByUserId(@Param("userId") Long userId);

    /**
     * Get the oldest repository entries for a user beyond a certain count.
     * Used to enforce max repository limit.
     */
    @Query("SELECT r FROM RepositoryHistory r WHERE r.userId = :userId ORDER BY r.lastAccessedAt ASC")
    List<RepositoryHistory> findOldestByUserId(@Param("userId") Long userId, Pageable pageable);
}
