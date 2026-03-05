package ai.mindvex.backend.controller;

import ai.mindvex.backend.entity.User;
import ai.mindvex.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * Simple repository cloning endpoint
 * Clones a repo using JGit and returns all files for frontend WebContainer
 */
@RestController
@RequestMapping("/api/repositories")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class RepositoryCloneController {

    private final UserRepository userRepository;

    @PostMapping("/clone")
    public ResponseEntity<CloneResponse> cloneRepository(
            @RequestBody CloneRequest request,
            Authentication authentication) {

        try {
            log.info("[Clone] Cloning repository: {}", request.url());

            // Get GitHub token if user is authenticated
            String githubToken = null;
            if (authentication != null) {
                try {
                    UserDetails userDetails = (UserDetails) authentication.getPrincipal();
                    User user = userRepository.findByEmail(userDetails.getUsername()).orElse(null);
                    if (user != null) {
                        githubToken = user.getGithubAccessToken();
                    }
                } catch (Exception e) {
                    log.debug("[Clone] No GitHub token available: {}", e.getMessage());
                }
            }

            // Create temp directory for cloning
            Path tempDir = Files.createTempDirectory("mindvex-clone-");
            log.info("[Clone] Cloning into temporary directory: {}", tempDir);

            try {
                // Clone the repository using JGit
                var cloneCmd = Git.cloneRepository()
                        .setURI(request.url())
                        .setDirectory(tempDir.toFile())
                        .setDepth(1); // shallow clone

                // Add authentication if available
                if (githubToken != null && !githubToken.isBlank()) {
                    log.info("[Clone] Using GitHub authentication");
                    cloneCmd.setCredentialsProvider(
                            new UsernamePasswordCredentialsProvider("oauth2", githubToken)
                    );
                }

                Git git = cloneCmd.call();
                git.close();

                log.info("[Clone] Repository cloned successfully");

                // Read all files from the cloned repository
                Map<String, FileData> files = new HashMap<>();
                readDirectory(tempDir, tempDir, files);

                log.info("[Clone] Read {} files from repository", files.size());

                // Clean up temp directory
                deleteDirectory(tempDir.toFile());

                return ResponseEntity.ok(new CloneResponse(
                        true,
                        "Repository cloned successfully",
                        tempDir.getFileName().toString(),
                        files
                ));

            } catch (Exception e) {
                // Clean up on error
                try {
                    deleteDirectory(tempDir.toFile());
                } catch (IOException ignored) {
                }
                throw e;
            }

        } catch (Exception e) {
            log.error("[Clone] Failed to clone repository: {}", e.getMessage(), e);
            String errorMessage = e.getMessage();

            // Provide user-friendly error messages
            if (errorMessage != null) {
                if (errorMessage.contains("not authorized") || errorMessage.contains("Authentication")) {
                    errorMessage = "Authentication failed. Please connect your GitHub account.";
                } else if (errorMessage.contains("not found") || errorMessage.contains("404")) {
                    errorMessage = "Repository not found. Please check the URL.";
                } else if (errorMessage.contains("timeout") || errorMessage.contains("connection")) {
                    errorMessage = "Connection timeout. Please try again.";
                }
            }

            return ResponseEntity.ok(new CloneResponse(
                    false,
                    "Failed to clone repository: " + errorMessage,
                    null,
                    null
            ));
        }
    }

    /**
     * Recursively read all files from a directory
     */
    private void readDirectory(Path baseDir, Path currentDir, Map<String, FileData> files) throws IOException {
        try (Stream<Path> paths = Files.list(currentDir)) {
            paths.forEach(path -> {
                try {
                    // Skip .git directory
                    if (path.getFileName().toString().equals(".git")) {
                        return;
                    }

                    if (Files.isDirectory(path)) {
                        readDirectory(baseDir, path, files);
                    } else if (Files.isRegularFile(path)) {
                        String relativePath = baseDir.relativize(path)
                                .toString()
                                .replace('\\', '/');

                        // Try to read as text, fallback to base64 for binary files
                        try {
                            String content = Files.readString(path, StandardCharsets.UTF_8);
                            files.put(relativePath, new FileData(content, "utf-8", false));
                        } catch (Exception e) {
                            // Binary file - encode as base64
                            byte[] bytes = Files.readAllBytes(path);
                            String base64 = Base64.getEncoder().encodeToString(bytes);
                            files.put(relativePath, new FileData(base64, "base64", true));
                        }
                    }
                } catch (IOException e) {
                    log.warn("[Clone] Failed to read file {}: {}", path, e.getMessage());
                }
            });
        }
    }

    /**
     * Delete directory and all contents
     */
    private void deleteDirectory(File directory) throws IOException {
        if (!directory.exists()) {
            return;
        }

        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    deleteDirectory(file);
                } else {
                    file.delete();
                }
            }
        }
        directory.delete();
    }

    // Request/Response DTOs
    public record CloneRequest(String url) {}

    public record CloneResponse(
            boolean success,
            String message,
            String workdir,
            Map<String, FileData> files
    ) {}

    public record FileData(
            String content,
            String encoding,
            boolean binary
    ) {}
}
