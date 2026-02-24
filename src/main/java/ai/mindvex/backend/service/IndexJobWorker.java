package ai.mindvex.backend.service;

import ai.mindvex.backend.entity.IndexJob;
import ai.mindvex.backend.repository.IndexJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Async job worker that polls the index_jobs table for pending jobs.
 *
 * Handles two job types:
 *   - "scip_index" : parse a SCIP binary and populate code_intelligence tables
 *   - "graph_build": clone repo, extract import-based dependencies, populate file_dependencies
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
     * graph_build: clone the repo, parse imports, save file dependency edges.
     * No SCIP CLI tools required — uses regex-based import extraction.
     */
    private void processGraphBuild(IndexJob job) throws Exception {
        log.info("[IndexJobWorker] Starting graph_build for repo={}", job.getRepoUrl());
        int edgeCount = sourceCodeDependencyExtractor.extractFromRepo(job.getUserId(), job.getRepoUrl());
        log.info("[IndexJobWorker] graph_build done: {} edges extracted for {}", edgeCount, job.getRepoUrl());
    }

    /**
     * scip_index: read a SCIP protobuf binary and ingest into code_intelligence tables.
     */
    private void processScipIndex(IndexJob job) throws Exception {
        Path payloadPath = Path.of(job.getPayloadPath());
        try (InputStream stream = Files.newInputStream(payloadPath)) {
            scipIngestionService.ingest(job.getUserId(), job.getRepoUrl(), stream);
        }
        // Clean up temp file after successful ingestion
        Files.deleteIfExists(payloadPath);
    }
}
