package ai.mindvex.backend.controller;

import ai.mindvex.backend.dto.BlameLineResponse;
import ai.mindvex.backend.dto.HotspotResponse;
import ai.mindvex.backend.dto.IndexJobResponse;
import ai.mindvex.backend.dto.WeeklyChurnResponse;
import ai.mindvex.backend.entity.FileChurnStat;
import ai.mindvex.backend.entity.IndexJob;
import ai.mindvex.backend.entity.User;
import ai.mindvex.backend.repository.FileChurnStatRepository;
import ai.mindvex.backend.repository.IndexJobRepository;
import ai.mindvex.backend.repository.UserRepository;
import ai.mindvex.backend.service.ChurnCalculationEngine;
import ai.mindvex.backend.service.JGitMiningService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * AnalyticsController
 *
 * Endpoints:
 * POST /api/analytics/mine — trigger JGit mining + churn aggregation (async via
 * job queue)
 * GET /api/analytics/hotspots — files with churn > threshold over last N weeks
 * GET /api/analytics/file-trend — week-by-week churn for a single file
 * GET /api/analytics/blame — line-level blame annotations
 */
@RestController
@RequestMapping("/api/analytics")
@RequiredArgsConstructor
@Slf4j
public class AnalyticsController {

        private final JGitMiningService miningService;
        private final ChurnCalculationEngine churnEngine;
        private final FileChurnStatRepository churnStatRepository;
        private final IndexJobRepository indexJobRepository;
        private final UserRepository userRepository;

        // ─── POST /api/analytics/mine ─────────────────────────────────────────────

        /**
         * Enqueue a git mining job. The IndexJobWorker picks it up within 5 seconds.
         * Returns the job ID for polling via GET /api/scip/jobs/{id}.
         */
        @PostMapping("/mine")
        public ResponseEntity<Map<String, Object>> triggerMining(
                        @RequestParam String repoUrl,
                        @RequestParam(defaultValue = "90") int days,
                        @AuthenticationPrincipal Jwt jwt) {
                Long userId = extractUserId(jwt);

                IndexJob job = new IndexJob();
                job.setUserId(userId);
                job.setRepoUrl(repoUrl);
                job.setStatus("pending");
                job.setJobType("git_mine");
                job.setPayload("{\"days\":" + days + "}");
                indexJobRepository.save(job);

                return ResponseEntity.accepted()
                                .body(Map.of("jobId", job.getId(), "status", "pending"));
        }

        // ─── GET /api/analytics/hotspots ─────────────────────────────────────────

        /**
         * Returns files with churn_rate > threshold over the last N weeks,
         * grouped by file with an aggregated summary and weekly trend.
         */
        @GetMapping("/hotspots")
        public ResponseEntity<List<HotspotResponse>> getHotspots(
                        @RequestParam String repoUrl,
                        @RequestParam(defaultValue = "12") int weeks,
                        @RequestParam(defaultValue = "25.0") double threshold,
                        @AuthenticationPrincipal Jwt jwt) {
                Long userId = extractUserId(jwt);
                LocalDate since = LocalDate.now().minusWeeks(weeks);

                List<FileChurnStat> stats = churnStatRepository.findHotspots(userId, repoUrl, since, threshold);

                // Group by filePath and build HotspotResponse
                Map<String, List<FileChurnStat>> byFile = stats.stream()
                                .collect(Collectors.groupingBy(FileChurnStat::getFilePath));

                List<HotspotResponse> hotspots = byFile.entrySet().stream()
                                .map(e -> buildHotspotResponse(e.getKey(), e.getValue()))
                                .sorted(Comparator.comparing(HotspotResponse::avgChurnRate).reversed())
                                .limit(20)
                                .toList();

                return ResponseEntity.ok(hotspots);
        }

        // ─── GET /api/analytics/file-trend ───────────────────────────────────────

        @GetMapping("/file-trend")
        public ResponseEntity<List<WeeklyChurnResponse>> getFileTrend(
                        @RequestParam String repoUrl,
                        @RequestParam String filePath,
                        @RequestParam(defaultValue = "12") int weeks,
                        @AuthenticationPrincipal Jwt jwt) {
                Long userId = extractUserId(jwt);
                LocalDate since = LocalDate.now().minusWeeks(weeks);

                List<WeeklyChurnResponse> trend = churnStatRepository
                                .findFileTrend(userId, repoUrl, filePath, since)
                                .stream()
                                .map(s -> new WeeklyChurnResponse(
                                                s.getWeekStart(),
                                                s.getLinesAdded(),
                                                s.getLinesDeleted(),
                                                s.getCommitCount(),
                                                s.getChurnRate()))
                                .toList();

                return ResponseEntity.ok(trend);
        }

        // ─── GET /api/analytics/blame ─────────────────────────────────────────────

        /**
         * Returns line-level blame annotations using JGit's BlameCommand.
         * The repository must have been cloned previously via /api/analytics/mine.
         */
        @GetMapping("/blame")
        public ResponseEntity<List<BlameLineResponse>> getBlame(
                        @RequestParam String repoUrl,
                        @RequestParam String filePath,
                        @AuthenticationPrincipal Jwt jwt) {
                Long userId = extractUserId(jwt);

                try {
                        User user = userRepository.findById(userId)
                                        .orElseThrow(() -> new IllegalStateException("User not found"));

                        List<BlameLineResponse> lines = miningService.blame(repoUrl, user.getGithubAccessToken(),
                                        filePath);
                        return ResponseEntity.ok(lines);
                } catch (Exception e) {
                        log.error("[Blame] Failed for {} / {}: {}", repoUrl, filePath, e.getMessage());
                        return ResponseEntity.internalServerError().build();
                }
        }

        // ─── Helpers ──────────────────────────────────────────────────────────────

        private HotspotResponse buildHotspotResponse(String filePath, List<FileChurnStat> stats) {
                int totalAdded = stats.stream().mapToInt(FileChurnStat::getLinesAdded).sum();
                int totalDeleted = stats.stream().mapToInt(FileChurnStat::getLinesDeleted).sum();
                int totalCommits = stats.stream().mapToInt(FileChurnStat::getCommitCount).sum();

                BigDecimal avgChurn = stats.stream()
                                .map(s -> s.getChurnRate() != null ? s.getChurnRate() : BigDecimal.ZERO)
                                .reduce(BigDecimal.ZERO, BigDecimal::add)
                                .divide(BigDecimal.valueOf(Math.max(stats.size(), 1)), 2, RoundingMode.HALF_UP);

                List<HotspotResponse.WeekPoint> trend = stats.stream()
                                .sorted(Comparator.comparing(FileChurnStat::getWeekStart))
                                .map(s -> new HotspotResponse.WeekPoint(
                                                s.getWeekStart(),
                                                s.getChurnRate() != null ? s.getChurnRate() : BigDecimal.ZERO,
                                                s.getCommitCount()))
                                .toList();

                return new HotspotResponse(filePath, avgChurn, totalCommits, totalAdded, totalDeleted, trend);
        }

        private Long extractUserId(Jwt jwt) {
                return Long.parseLong(jwt.getSubject());
        }
}
