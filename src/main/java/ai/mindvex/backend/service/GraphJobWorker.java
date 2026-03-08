package ai.mindvex.backend.service;

import ai.mindvex.backend.controller.WebSocketGraphController;
import ai.mindvex.backend.dto.GraphEdgeDto;
import ai.mindvex.backend.dto.GraphNodeDto;
import ai.mindvex.backend.dto.GraphUpdateMessage;
import ai.mindvex.backend.entity.IndexJob;
import ai.mindvex.backend.entity.User;
import ai.mindvex.backend.repository.IndexJobRepository;
import ai.mindvex.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Async job worker that polls the index_jobs table for pending graph_build jobs.
 *
 * When a graph_build job is found, it uses SourceCodeDependencyExtractor to
 * clone the repo, parse import statements, and populate file_dependencies.
 * 
 * Now enhanced with WebSocket support for real-time graph updates.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GraphJobWorker {

    private final IndexJobRepository indexJobRepository;
    private final SourceCodeDependencyExtractor sourceCodeExtractor;
    private final UserRepository userRepository;
    private final WebSocketGraphController webSocketController;

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
        
        // Extract repoId for WebSocket topic
        String repoId = extractRepoId(job.getRepoUrl());

        try {
            // Send initial connection message
            broadcastStartMessage(repoId);

            // Fetch user's GitHub access token for private repository support
            String accessToken = userRepository.findById(job.getUserId())
                    .map(User::getGithubAccessToken)
                    .orElse(null);

            // Extract dependencies by cloning + parsing imports
            int edgesExtracted = sourceCodeExtractor.extractFromRepo(
                    job.getUserId(),
                    job.getRepoUrl(),
                    accessToken);

            // Send completion notification via WebSocket
            webSocketController.sendCompletionNotification(repoId, 0, edgesExtracted);

            job.setStatus("done");
            job.setFinishedAt(LocalDateTime.now());
            job.setPayload("{\"edges\": " + edgesExtracted + "}");
            log.info("graph_build job id={} completed successfully. Extracted {} edges.", job.getId(), edgesExtracted);

        } catch (Exception e) {
            log.error("graph_build job id={} failed: {}", job.getId(), e.getMessage(), e);
            
            // Send error notification via WebSocket
            broadcastErrorMessage(repoId, e.getMessage());
            
            job.setStatus("failed");
            job.setErrorMsg(e.getMessage());
            job.setFinishedAt(LocalDateTime.now());
        }

        indexJobRepository.save(job);
    }
    
    /**
     * Extract repository ID from URL for WebSocket topic identification
     */
    private String extractRepoId(String repoUrl) {
        // Simple extraction: take the repo name from URL
        // e.g., "https://github.com/user/repo" -> "user-repo"
        String[] parts = repoUrl.replaceAll("\\.git$", "").split("/");
        if (parts.length >= 2) {
            return parts[parts.length - 2] + "-" + parts[parts.length - 1];
        }
        return repoUrl.replaceAll("[^a-zA-Z0-9-]", "-");
    }
    
    /**
     * Broadcast job start message to WebSocket subscribers
     */
    private void broadcastStartMessage(String repoId) {
        GraphUpdateMessage message = GraphUpdateMessage.builder()
                .type("job_started")
                .repoId(repoId)
                .timestamp(System.currentTimeMillis())
                .metadata(GraphUpdateMessage.UpdateMetadata.builder()
                        .status("processing")
                        .message("Graph extraction started")
                        .build())
                .build();
        
        webSocketController.broadcastGraphUpdate(repoId, message);
    }
    
    /**
     * Broadcast error message to WebSocket subscribers
     */
    private void broadcastErrorMessage(String repoId, String errorMsg) {
        GraphUpdateMessage message = GraphUpdateMessage.builder()
                .type("error")
                .repoId(repoId)
                .timestamp(System.currentTimeMillis())
                .metadata(GraphUpdateMessage.UpdateMetadata.builder()
                        .status("failed")
                        .message(errorMsg)
                        .build())
                .build();
        
        webSocketController.broadcastGraphUpdate(repoId, message);
    }
}
