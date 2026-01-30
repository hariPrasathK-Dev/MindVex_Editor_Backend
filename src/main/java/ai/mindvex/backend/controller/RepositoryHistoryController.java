package ai.mindvex.backend.controller;

import ai.mindvex.backend.dto.RepositoryHistoryRequest;
import ai.mindvex.backend.dto.RepositoryHistoryResponse;
import ai.mindvex.backend.service.RepositoryHistoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST controller for managing repository history.
 * Provides endpoints for CRUD operations on repository import history.
 */
@RestController
@RequestMapping("/api/repository-history")
@RequiredArgsConstructor
@Tag(name = "Repository History", description = "Repository import history management endpoints")
@SecurityRequirement(name = "Bearer Authentication")
public class RepositoryHistoryController {

    private final RepositoryHistoryService repositoryHistoryService;
    private final ai.mindvex.backend.repository.UserRepository userRepository;

    @GetMapping
    @Operation(summary = "Get repository history", description = "Retrieves all repository history for the authenticated user, ordered by last accessed date")
    public ResponseEntity<List<RepositoryHistoryResponse>> getRepositoryHistory(
            @RequestParam(required = false, defaultValue = "50") Integer limit,
            Authentication authentication) {

        Long userId = getUserIdFromAuthentication(authentication);
        List<RepositoryHistoryResponse> history;

        if (limit != null && limit < 50) {
            history = repositoryHistoryService.getRepositoryHistory(userId, limit);
        } else {
            history = repositoryHistoryService.getRepositoryHistory(userId);
        }

        return ResponseEntity.ok(history);
    }

    @PostMapping
    @Operation(summary = "Add repository to history", description = "Adds a repository to the user's import history. If the repository already exists, updates the last accessed timestamp.")
    public ResponseEntity<RepositoryHistoryResponse> addRepository(
            @Valid @RequestBody RepositoryHistoryRequest request,
            Authentication authentication) {

        Long userId = getUserIdFromAuthentication(authentication);
        RepositoryHistoryResponse response = repositoryHistoryService.addRepository(userId, request);
        return new ResponseEntity<>(response, HttpStatus.CREATED);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Remove repository from history", description = "Removes a specific repository from the user's import history")
    public ResponseEntity<Void> removeRepository(
            @PathVariable Long id,
            Authentication authentication) {

        Long userId = getUserIdFromAuthentication(authentication);
        repositoryHistoryService.removeRepository(userId, id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping
    @Operation(summary = "Clear all repository history", description = "Removes all repositories from the user's import history")
    public ResponseEntity<Void> clearHistory(Authentication authentication) {

        Long userId = getUserIdFromAuthentication(authentication);
        repositoryHistoryService.clearHistory(userId);
        return ResponseEntity.noContent().build();
    }

    private Long getUserIdFromAuthentication(Authentication authentication) {
        UserDetails userDetails = (UserDetails) authentication.getPrincipal();
        return userRepository.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new RuntimeException("User not found"))
                .getId();
    }
}
