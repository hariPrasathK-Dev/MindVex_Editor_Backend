package ai.mindvex.backend.reasoning.controller;

import ai.mindvex.backend.entity.User;
import ai.mindvex.backend.repository.UserRepository;
import ai.mindvex.backend.reasoning.dto.ReasoningResultDto;
import ai.mindvex.backend.reasoning.service.CodeReasoningEngine;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * High-performance REST Controller for triggering massive AI reasoning scans.
 */
@RestController
@RequestMapping("/api/mcp/reasoning")
@RequiredArgsConstructor
@Slf4j
public class ReasoningController {

    private final CodeReasoningEngine reasoningEngine;
    private final UserRepository userRepository;

    @PostMapping("/analyze")
    public ResponseEntity<ReasoningResultDto> performReasoningScan(
            @RequestBody Map<String, Object> payload,
            Authentication authentication) {

        log.info("Received request for deep architectural AI reasoning analysis.");
        String repoUrl = (String) payload.get("repoUrl");

        @SuppressWarnings("unchecked")
        Map<String, Object> aiProviderConfig = payload.get("providerConfig") instanceof Map
            ? (Map<String, Object>) payload.get("providerConfig")
            : (Map<String, Object>) payload.get("provider");

        if (repoUrl == null || repoUrl.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        if (aiProviderConfig == null) {
            aiProviderConfig = Map.of("name", "Google");
        }

        Long userId = extractUserId(authentication);

        try {
            ReasoningResultDto result = reasoningEngine.performDeepReasoning(userId, repoUrl, aiProviderConfig);
            return ResponseEntity.ok(result);
        } catch (Exception ex) {
            log.error("Reasoning scan failed for repo {}: {}", repoUrl, ex.getMessage(), ex);
            ReasoningResultDto fallback = new ReasoningResultDto();
            fallback.setRepositoryUrl(repoUrl);
            fallback.setAnalysisTimestamp(java.time.Instant.now().toString());
            fallback.setAnalysisDurationMs(0L);
            fallback.setFilesScanned(0);
            fallback.setDetectedPatterns(java.util.List.of());
            fallback.setAntiPatterns(java.util.List.of());
            fallback.setRefactoringSuggestions(java.util.List.of());
            fallback.setSuggestedBoundaries(java.util.List.of());
            return ResponseEntity.ok(fallback);
        }
    }

    private Long extractUserId(Authentication authentication) {
        if (authentication == null || authentication.getPrincipal() == null) {
            return 1L; // Fallback for dev mode
        }

        if (authentication.getPrincipal() instanceof UserDetails) {
            String username = ((UserDetails) authentication.getPrincipal()).getUsername();
            return userRepository.findByEmail(username)
                    .map(User::getId)
                    .orElse(1L);
        }

        try {
            return Long.parseLong(authentication.getPrincipal().toString());
        } catch (NumberFormatException ex) {
            return 1L;
        }
    }
}
