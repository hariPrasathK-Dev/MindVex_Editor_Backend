package ai.mindvex.backend.service;

import ai.mindvex.backend.dto.CommitFileDiff;
import ai.mindvex.backend.entity.IndexJob;
import ai.mindvex.backend.entity.User;
import ai.mindvex.backend.repository.IndexJobRepository;
import ai.mindvex.backend.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Async job worker that polls the index_jobs table for pending jobs.
 *
 * Handles three job types:
 * - "scip_index" : parse a SCIP binary and populate code_intelligence tables
 * - "graph_build": clone repo, extract import-based dependencies, populate
 * file_dependencies
 * - "git_mine" : clone repo, mine commit history, calculate churn statistics
 *
 * Uses SELECT ... FOR UPDATE SKIP LOCKED so multiple worker instances
 * can run concurrently without double-processing the same job.
 *
 * Poll interval: 5 seconds (configurable via app.scip.worker.interval-ms).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class IndexJobWorker {

    private final IndexJobRepository indexJobRepository;
    private final ScipIngestionService scipIngestionService;
    private final SourceCodeDependencyExtractor sourceCodeDependencyExtractor;
    private final EmbeddingIngestionService embeddingIngestionService;
    private final JGitMiningService jgitMiningService;
    private final ChurnCalculationEngine churnEngine;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Scheduled(fixedDelayString = "${app.scip.worker.interval-ms:5000}")
    @Transactional
    public void processNextJob() {
        Optional<IndexJob> jobOpt = indexJobRepository.claimNextPendingJob();
        if (jobOpt.isEmpty())
            return;

        IndexJob job = jobOpt.get();
        log.info("Processing job id={} type={} repo={}", job.getId(), job.getJobType(), job.getRepoUrl());

        job.setStatus("processing");
        job.setStartedAt(LocalDateTime.now());
        indexJobRepository.save(job);

        try {
            String jobType = job.getJobType() != null ? job.getJobType() : "scip_index";

            switch (jobType) {
                case "graph_build" -> processGraphBuild(job);
                case "git_mine" -> processGitMine(job);
                case "scip_index" -> processScipIndex(job);
                default -> {
                    log.warn("Unknown job type '{}' for job id={}, treating as scip_index", jobType, job.getId());
                    processScipIndex(job);
                }
            }

            job.setStatus("done");
            job.setFinishedAt(LocalDateTime.now());
            log.info("Job id={} type={} completed successfully", job.getId(), job.getJobType());

        } catch (Exception e) {
            log.error("Job id={} type={} failed: {}", job.getId(), job.getJobType(), e.getMessage(), e);
            job.setStatus("failed");
            job.setErrorMsg(e.getMessage());
            job.setFinishedAt(LocalDateTime.now());
        }

        indexJobRepository.save(job);
    }

    /**
     * graph_build: clone the repo, parse imports, save file dependency edges, and
     * generate embeddings.
     * No SCIP CLI tools required — uses regex-based import extraction + Gemini
     * embeddings.
     */
    private void processGraphBuild(IndexJob job) throws Exception {
        log.info("[IndexJobWorker] Starting graph_build for repo={}", job.getRepoUrl());

        // Fetch user's GitHub access token for private repository support
        String accessToken = getUserGithubToken(job.getUserId());

        // Step 1: Extract dependency graph
        int edgeCount = sourceCodeDependencyExtractor.extractFromRepo(
                job.getUserId(),
                job.getRepoUrl(),
                accessToken);
        log.info("[IndexJobWorker] Dependency extraction done: {} edges extracted for {}", edgeCount, job.getRepoUrl());

        // Step 2: Generate vector embeddings for semantic search
        int embeddingCount = embeddingIngestionService.extractAndIngestRepo(
                job.getUserId(),
                job.getRepoUrl(),
                accessToken);
        log.info("[IndexJobWorker] Embedding generation done: {} chunks embedded for {}", embeddingCount,
                job.getRepoUrl());

        log.info("[IndexJobWorker] graph_build completed successfully for {}", job.getRepoUrl());
    }

    /**
     * git_mine: clone the repo, mine commit history, calculate churn statistics.
     * Uses JGit to traverse commits and compute per-file change metrics.
     */
    private void processGitMine(IndexJob job) throws Exception {
        log.info("[IndexJobWorker] Starting git_mine for repo={}", job.getRepoUrl());

        // Parse payload to get 'days' parameter
        int days = 90; // default
        if (job.getPayload() != null && !job.getPayload().isBlank()) {
            try {
                Map<String, Object> payload = objectMapper.readValue(job.getPayload(), Map.class);
                if (payload.containsKey("days")) {
                    days = (Integer) payload.get("days");
                }
            } catch (Exception e) {
                log.warn("[IndexJobWorker] Failed to parse payload for job {}: {}", job.getId(), e.getMessage());
            }
        }

        // Fetch user's GitHub access token for private repository support
        String accessToken = getUserGithubToken(job.getUserId());

        // Mine commit history
        Instant since = Instant.now().minusSeconds(days * 24L * 3600L);
        List<CommitFileDiff> diffs = jgitMiningService.mineHistory(
                job.getUserId(),
                job.getRepoUrl(),
                accessToken,
                since);

        // Aggregate into weekly churn statistics
        churnEngine.aggregate(job.getUserId(), job.getRepoUrl(), diffs);

        log.info("[IndexJobWorker] git_mine done: {} diffs mined for {}", diffs.size(), job.getRepoUrl());
    }

    /**
     * scip_index: read a SCIP protobuf binary and ingest into code_intelligence
     * tables.
     */
    private void processScipIndex(IndexJob job) throws Exception {
        Path payloadPath = Path.of(job.getPayloadPath());
        try (InputStream stream = Files.newInputStream(payloadPath)) {
            scipIngestionService.ingest(job.getUserId(), job.getRepoUrl(), stream);
        }
        // Clean up temp file after successful ingestion
        Files.deleteIfExists(payloadPath);
    }

    /**
     * Fetch user's GitHub access token from the database.
     * Returns null if user not found or token not set.
     */
    private String getUserGithubToken(Long userId) {
        return userRepository.findById(userId)
                .map(User::getGithubAccessToken)
                .orElse(null);
    }
}
