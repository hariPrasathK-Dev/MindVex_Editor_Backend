package ai.mindvex.backend.repository;

import ai.mindvex.backend.entity.FileChurnStat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface FileChurnStatRepository extends JpaRepository<FileChurnStat, Long> {

    Optional<FileChurnStat> findByUserIdAndRepoUrlAndFilePathAndWeekStart(
            Long userId, String repoUrl, String filePath, LocalDate weekStart);

    /**
     * Returns the top N hotspot files (highest avg churn rate) over the last N
     * weeks.
     */
    @Query("""
            SELECT f FROM FileChurnStat f
            WHERE f.userId = :userId
              AND f.repoUrl = :repoUrl
              AND f.weekStart >= :since
              AND f.churnRate > :threshold
            ORDER BY f.churnRate DESC
            """)
    List<FileChurnStat> findHotspots(
            @Param("userId") Long userId,
            @Param("repoUrl") String repoUrl,
            @Param("since") LocalDate since,
            @Param("threshold") double threshold);

    /**
     * Returns week-by-week churn for a single file (for trend charts).
     */
    @Query("""
            SELECT f FROM FileChurnStat f
            WHERE f.userId = :userId
              AND f.repoUrl = :repoUrl
              AND f.filePath = :filePath
              AND f.weekStart >= :since
            ORDER BY f.weekStart ASC
            """)
    List<FileChurnStat> findFileTrend(
            @Param("userId") Long userId,
            @Param("repoUrl") String repoUrl,
            @Param("filePath") String filePath,
            @Param("since") LocalDate since);
}
