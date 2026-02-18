package ai.mindvex.backend.dto;

import java.time.Instant;

/**
 * Represents a single file's diff data from one commit.
 * Produced by JGitMiningService and consumed by ChurnCalculationEngine.
 */
public record CommitFileDiff(
        String commitHash,
        String filePath,
        Instant committedAt,
        String authorEmail,
        int linesAdded,
        int linesDeleted) {
}
