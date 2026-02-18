package ai.mindvex.backend.repository;

import ai.mindvex.backend.entity.CommitStat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CommitStatRepository extends JpaRepository<CommitStat, Long> {

    boolean existsByUserIdAndRepoUrlAndCommitHash(Long userId, String repoUrl, String commitHash);
}
