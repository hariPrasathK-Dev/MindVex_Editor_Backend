package ai.mindvex.backend.service;

import ai.mindvex.backend.entity.IndexJob;
import ai.mindvex.backend.repository.IndexJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Async job worker that polls the index_jobs table for pending SCIP index jobs.
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

    @Scheduled(fixedDelayString = "${app.scip.worker.interval-ms:5000}")
    @Transactional
    public void processNextJob() {
        Optional<IndexJob> jobOpt = indexJobRepository.claimNextPendingJob();
        if (jobOpt.isEmpty())
            return;

        IndexJob job = jobOpt.get();
        log.info("Processing SCIP index job id={} repo={}", job.getId(), job.getRepoUrl());

        job.setStatus("processing");
        job.setStartedAt(LocalDateTime.now());
        indexJobRepository.save(job);

        try {
            Path payloadPath = Path.of(job.getPayloadPath());
            try (InputStream stream = Files.newInputStream(payloadPath)) {
                scipIngestionService.ingest(job.getUserId(), job.getRepoUrl(), stream);
            }
            // Clean up temp file after successful ingestion
            Files.deleteIfExists(payloadPath);

            job.setStatus("done");
            job.setFinishedAt(LocalDateTime.now());
            log.info("SCIP index job id={} completed successfully", job.getId());

        } catch (Exception e) {
            log.error("SCIP index job id={} failed: {}", job.getId(), e.getMessage(), e);
            job.setStatus("failed");
            job.setErrorMsg(e.getMessage());
            job.setFinishedAt(LocalDateTime.now());
        }

        indexJobRepository.save(job);
    }
}
