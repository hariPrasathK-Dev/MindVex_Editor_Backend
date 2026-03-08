package ai.mindvex.backend.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Settings Controller
 *
 * Provides application configuration and status to the frontend.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Slf4j
public class SettingsController {

    @Value("${gemini.api-key:#{null}}")
    private String geminiApiKey;

    @Value("${GITHUB_CLIENT_ID:#{null}}")
    private String githubClientId;

    /**
     * Get a list of configured AI providers on the backend.
     * Tells the frontend which models are "ready to use" via env vars.
     */
    @GetMapping("/configured-providers")
    public ResponseEntity<List<Map<String, Object>>> getConfiguredProviders() {
        log.debug("[Settings] Fetching configured providers...");

        List<Map<String, Object>> providers = new ArrayList<>();

        // Gemini is currently the primary backend provider
        providers.add(Map.of(
                "name", "Google",
                "isConfigured", geminiApiKey != null && !geminiApiKey.isEmpty(),
                "configMethod", "environment"));

        // GitHub OAuth is used for proxying requests
        providers.add(Map.of(
                "name", "GitHub",
                "isConfigured", githubClientId != null && !githubClientId.isEmpty(),
                "configMethod", "environment"));

        log.debug("[Settings] Configured providers: {}", providers.size());
        return ResponseEntity.ok(providers);
    }
}
