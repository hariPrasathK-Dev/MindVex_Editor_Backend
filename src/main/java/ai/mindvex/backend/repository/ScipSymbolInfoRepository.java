package ai.mindvex.backend.repository;

import ai.mindvex.backend.entity.ScipSymbolInfo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ScipSymbolInfoRepository extends JpaRepository<ScipSymbolInfo, Long> {

    Optional<ScipSymbolInfo> findByUserIdAndRepoUrlAndSymbol(
            Long userId, String repoUrl, String symbol);
}
