package ai.mindvex.backend.controller;

import ai.mindvex.backend.dto.HoverResponse;
import ai.mindvex.backend.dto.IndexJobResponse;
import ai.mindvex.backend.entity.IndexJob;
import ai.mindvex.backend.entity.User;
import ai.mindvex.backend.exception.ResourceNotFoundException;
import ai.mindvex.backend.repository.IndexJobRepository;
import ai.mindvex.backend.repository.UserRepository;
import ai.mindvex.backend.service.ScipQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;
import java.util.Optional;

/**
 * REST controller for SCIP index upload and semantic hover queries.
 *
 * POST /api/scip/upload?repoUrl=<url>
 * Accepts a raw .scip binary (multipart), enqueues an index_job,
 * returns { jobId }.
 *
 * GET /api/scip/hover?repoUrl=<url>&filePath=<path>&line=<n>&character=<n>
 * Returns hover data for the given cursor position.
 *
 * GET /api/scip/jobs/{id}
 * Returns the status of an index job.
 */
@RestController
@RequestMapping("/api/scip")
@RequiredArgsConstructor
@Slf4j
public class ScipController {

    private final UserRepository userRepository;
    private final IndexJobRepository indexJobRepository;
    private final ScipQueryService scipQueryService;

    // ─── Upload ───────────────────────────────────────────────────────────────

    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> upload(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam("repoUrl") String repoUrl,
            @RequestParam("file") MultipartFile file) throws IOException {

        User user = resolveUser(userDetails);

        // Save the binary to a temp file so the worker can read it later
        File tempFile = File.createTempFile("scip-", ".bin");
        file.transferTo(tempFile);

        IndexJob job = IndexJob.builder()
                .userId(user.getId())
                .repoUrl(repoUrl)
                .status("pending")
                .payloadPath(tempFile.getAbsolutePath())
                .build();
        job = indexJobRepository.save(job);

        log.info("Enqueued SCIP index job id={} for user={} repo={}", job.getId(), user.getId(), repoUrl);

        return ResponseEntity.accepted().body(Map.of("jobId", job.getId()));
    }

    // ─── Hover ────────────────────────────────────────────────────────────────

    @GetMapping("/hover")
    public ResponseEntity<HoverResponse> hover(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam("repoUrl") String repoUrl,
            @RequestParam("filePath") String filePath,
            @RequestParam("line") int line,
            @RequestParam("character") int character) {

        User user = resolveUser(userDetails);

        Optional<HoverResponse> result = scipQueryService.getHover(user.getId(), repoUrl, filePath, line, character);

        return result
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    // ─── Job Status ───────────────────────────────────────────────────────────

    @GetMapping("/jobs/{id}")
    public ResponseEntity<IndexJobResponse> getJobStatus(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long id) {

        User user = resolveUser(userDetails);

        IndexJob job = indexJobRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Job not found: " + id));

        // Users can only see their own jobs
        if (!job.getUserId().equals(user.getId())) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(IndexJobResponse.builder()
                .id(job.getId())
                .repoUrl(job.getRepoUrl())
                .status(job.getStatus())
                .errorMsg(job.getErrorMsg())
                .createdAt(job.getCreatedAt())
                .startedAt(job.getStartedAt())
                .finishedAt(job.getFinishedAt())
                .build());
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private User resolveUser(UserDetails userDetails) {
        return userRepository.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }
}
