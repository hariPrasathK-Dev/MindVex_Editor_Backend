package ai.mindvex.backend.service;

import ai.mindvex.backend.entity.IndexJob;
import ai.mindvex.backend.repository.IndexJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Async job worker that polls the index_jobs table for pending graph_build jobs.
 *
 * When a graph_build job is found, it uses SourceCodeDependencyExtractor to
 * clone the repo, parse import statements, and populate file_dependencies.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GraphJobWorker {

    private final IndexJobRepository indexJobRepository;
    private final SourceCodeDependencyExtractor sourceCodeExtractor;

    @Scheduled(fixedDelayString = "${app.graph.worker.interval-ms:5000}")
    @Transactional
    public void processNextJob() {
        Optional<IndexJob> jobOpt = indexJobRepository.claimNextPendingJobType("graph_build");
        if (jobOpt.isEmpty())
            return;

        IndexJob job = jobOpt.get();
        log.info("Processing graph_build job id={} repo={}", job.getId(), job.getRepoUrl());

        job.setStatus("processing");
        job.setStartedAt(LocalDateTime.now());
        indexJobRepository.save(job);

        try {
            // Extract dependencies by cloning + parsing imports
            int edgesExtracted = sourceCodeExtractor.extractFromRepo(job.getUserId(), job.getRepoUrl());

            job.setStatus("done");
            job.setFinishedAt(LocalDateTime.now());
            job.setPayload("{\"edges\": " + edgesExtracted + "}");
            log.info("graph_build job id={} completed successfully. Extracted {} edges.", job.getId(), edgesExtracted);

        } catch (Exception e) {
            log.error("graph_build job id={} failed: {}", job.getId(), e.getMessage(), e);
            job.setStatus("failed");
            job.setErrorMsg(e.getMessage());
            job.setFinishedAt(LocalDateTime.now());
        }

        indexJobRepository.save(job);
    }
}
