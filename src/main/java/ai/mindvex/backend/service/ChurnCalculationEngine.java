package ai.mindvex.backend.service;

import ai.mindvex.backend.dto.CommitFileDiff;
import ai.mindvex.backend.entity.FileChurnStat;
import ai.mindvex.backend.repository.FileChurnStatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ChurnCalculationEngine
 *
 * Aggregates raw CommitFileDiff records into weekly churn statistics
 * and upserts them into git_analytics.file_churn_stats.
 *
 * churn_rate = (lines_added + lines_deleted) / estimated_total_lines * 100
 *
 * Total lines is estimated as max(added, 1) for the first week of a file,
 * then accumulated across weeks. This is a heuristic — accurate line counts
 * would require a full tree checkout which is expensive.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChurnCalculationEngine {

    private final FileChurnStatRepository churnStatRepository;

    // ─── Aggregation key ──────────────────────────────────────────────────────

    private record WeekFileKey(String filePath, LocalDate weekStart) {
    }

    private static class WeekAccumulator {
        int linesAdded;
        int linesDeleted;
        int commitCount;
    }

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * Aggregate a list of CommitFileDiff records into weekly churn stats
     * and upsert them into the database.
     *
     * @param userId  owner user ID
     * @param repoUrl repository URL
     * @param diffs   raw per-file diffs from JGitMiningService
     */
    @Transactional
    public void aggregate(Long userId, String repoUrl, List<CommitFileDiff> diffs) {
        // Group diffs by (filePath, isoWeekStart)
        Map<WeekFileKey, WeekAccumulator> buckets = new HashMap<>();

        for (CommitFileDiff diff : diffs) {
            LocalDate weekStart = toMondayOfWeek(
                    diff.committedAt().atZone(ZoneOffset.UTC).toLocalDate());
            WeekFileKey key = new WeekFileKey(diff.filePath(), weekStart);
            WeekAccumulator acc = buckets.computeIfAbsent(key, k -> new WeekAccumulator());
            acc.linesAdded += diff.linesAdded();
            acc.linesDeleted += diff.linesDeleted();
            acc.commitCount += 1;
        }

        // Upsert each bucket
        for (Map.Entry<WeekFileKey, WeekAccumulator> entry : buckets.entrySet()) {
            WeekFileKey key = entry.getKey();
            WeekAccumulator acc = entry.getValue();

            FileChurnStat stat = churnStatRepository
                    .findByUserIdAndRepoUrlAndFilePathAndWeekStart(
                            userId, repoUrl, key.filePath(), key.weekStart())
                    .orElseGet(FileChurnStat::new);

            stat.setUserId(userId);
            stat.setRepoUrl(repoUrl);
            stat.setFilePath(key.filePath());
            stat.setWeekStart(key.weekStart());
            stat.setLinesAdded(stat.getLinesAdded() + acc.linesAdded);
            stat.setLinesDeleted(stat.getLinesDeleted() + acc.linesDeleted);
            stat.setCommitCount(stat.getCommitCount() + acc.commitCount);

            // Churn rate heuristic: treat total changed lines as proxy for file size
            int totalChanged = stat.getLinesAdded() + stat.getLinesDeleted();
            int estimatedSize = Math.max(stat.getLinesAdded(), 50); // floor at 50 lines
            BigDecimal rate = BigDecimal.valueOf(totalChanged)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(estimatedSize), 2, RoundingMode.HALF_UP);
            stat.setChurnRate(rate);

            churnStatRepository.save(stat);
        }

        log.info("[ChurnEngine] Upserted {} weekly churn records for {}", buckets.size(), repoUrl);
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /** Returns the Monday of the ISO week containing the given date. */
    private LocalDate toMondayOfWeek(LocalDate date) {
        return date.with(DayOfWeek.MONDAY);
    }
}
