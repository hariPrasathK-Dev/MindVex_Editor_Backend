package ai.mindvex.backend.service;

import ai.mindvex.backend.dto.RepositoryHistoryRequest;
import ai.mindvex.backend.dto.RepositoryHistoryResponse;
import ai.mindvex.backend.entity.RepositoryHistory;
import ai.mindvex.backend.repository.RepositoryHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Service for managing repository history.
 * Enforces a maximum of 50 repositories per user.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RepositoryHistoryService {

    private static final int MAX_REPOSITORIES_PER_USER = 50;

    private final RepositoryHistoryRepository repositoryHistoryRepository;

    /**
     * Get all repository history for a user, ordered by last accessed date.
     */
    @Transactional(readOnly = true)
    public List<RepositoryHistoryResponse> getRepositoryHistory(Long userId) {
        return repositoryHistoryRepository.findByUserIdOrderByLastAccessedAtDesc(userId)
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    /**
     * Get repository history for a user with limit.
     */
    @Transactional(readOnly = true)
    public List<RepositoryHistoryResponse> getRepositoryHistory(Long userId, int limit) {
        return repositoryHistoryRepository.findByUserId(userId, PageRequest.of(0, limit))
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    /**
     * Add or update a repository in the user's history.
     * If the repository already exists, updates the last accessed timestamp.
     * Enforces the maximum repository limit.
     */
    @Transactional
    public RepositoryHistoryResponse addRepository(Long userId, RepositoryHistoryRequest request) {
        // Check if repository already exists for this user
        var existingRepo = repositoryHistoryRepository.findByUserIdAndUrl(userId, request.getUrl());

        if (existingRepo.isPresent()) {
            // Update existing entry
            var repo = existingRepo.get();
            repo.setLastAccessedAt(LocalDateTime.now());
            repo.setName(request.getName());
            if (request.getDescription() != null) {
                repo.setDescription(request.getDescription());
            }
            if (request.getBranch() != null) {
                repo.setBranch(request.getBranch());
            }
            if (request.getCommitHash() != null) {
                repo.setCommitHash(request.getCommitHash());
            }

            var savedRepo = repositoryHistoryRepository.save(repo);
            log.info("Updated repository history entry for user {}: {}", userId, request.getUrl());
            return mapToResponse(savedRepo);
        }

        // Check if user has reached the limit
        long currentCount = repositoryHistoryRepository.countByUserId(userId);
        if (currentCount >= MAX_REPOSITORIES_PER_USER) {
            // Remove oldest entries to make room
            int entriesToRemove = (int) (currentCount - MAX_REPOSITORIES_PER_USER + 1);
            var oldestEntries = repositoryHistoryRepository.findOldestByUserId(
                    userId, PageRequest.of(0, entriesToRemove));

            for (var entry : oldestEntries) {
                repositoryHistoryRepository.delete(entry);
                log.info("Removed old repository history entry for user {}: {}", userId, entry.getUrl());
            }
        }

        // Create new entry
        var newRepo = RepositoryHistory.builder()
                .userId(userId)
                .url(request.getUrl())
                .name(request.getName())
                .description(request.getDescription())
                .branch(request.getBranch())
                .commitHash(request.getCommitHash())
                .createdAt(LocalDateTime.now())
                .lastAccessedAt(LocalDateTime.now())
                .build();

        var savedRepo = repositoryHistoryRepository.save(newRepo);
        log.info("Added new repository history entry for user {}: {}", userId, request.getUrl());
        return mapToResponse(savedRepo);
    }

    /**
     * Remove a repository from the user's history.
     */
    @Transactional
    public void removeRepository(Long userId, Long repositoryId) {
        var repo = repositoryHistoryRepository.findByIdAndUserId(repositoryId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Repository not found or access denied"));

        repositoryHistoryRepository.delete(repo);
        log.info("Removed repository history entry {} for user {}", repositoryId, userId);
    }

    /**
     * Clear all repository history for a user.
     */
    @Transactional
    public void clearHistory(Long userId) {
        repositoryHistoryRepository.deleteAllByUserId(userId);
        log.info("Cleared all repository history for user {}", userId);
    }

    /**
     * Map entity to response DTO.
     */
    private RepositoryHistoryResponse mapToResponse(RepositoryHistory entity) {
        return RepositoryHistoryResponse.builder()
                .id(entity.getId())
                .url(entity.getUrl())
                .name(entity.getName())
                .description(entity.getDescription())
                .branch(entity.getBranch())
                .commitHash(entity.getCommitHash())
                .createdAt(entity.getCreatedAt())
                .lastAccessedAt(entity.getLastAccessedAt())
                .build();
    }
}
