package ai.mindvex.backend.reasoning.controller;

import ai.mindvex.backend.controller.McpController;
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

    @PostMapping("/analyze")
    public ResponseEntity<ReasoningResultDto> performReasoningScan(
            @RequestBody Map<String, Object> payload,
            Authentication authentication) {

        log.info("Received request for deep architectural AI reasoning analysis.");
        String repoUrl = (String) payload.get("repoUrl");
        Map<String, Object> aiProviderConfig = (Map<String, Object>) payload.get("providerConfig");

        if (repoUrl == null || aiProviderConfig == null) {
            return ResponseEntity.badRequest().build();
        }

        Long userId = extractUserId(authentication);
        ReasoningResultDto result = reasoningEngine.performDeepReasoning(userId, repoUrl, aiProviderConfig);
        
        return ResponseEntity.ok(result);
    }

    private Long extractUserId(Authentication authentication) {
        if (authentication == null || authentication.getPrincipal() == null) {
            return 1L; // Fallback for dev mode
        }
        if (authentication.getPrincipal() instanceof UserDetails) {
            return Long.parseLong(((UserDetails) authentication.getPrincipal()).getUsername());
        }
        return Long.parseLong(authentication.getPrincipal().toString());
    }
}
