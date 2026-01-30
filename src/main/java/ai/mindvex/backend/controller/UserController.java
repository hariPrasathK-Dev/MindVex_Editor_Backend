package ai.mindvex.backend.controller;

import ai.mindvex.backend.dto.UserResponse;
import ai.mindvex.backend.entity.User;
import ai.mindvex.backend.repository.UserRepository;
import ai.mindvex.backend.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@Tag(name = "Users", description = "User profile management endpoints")
@SecurityRequirement(name = "Bearer Authentication")
public class UserController {

    private final UserService userService;
    private final UserRepository userRepository;

    @GetMapping("/me")
    @Operation(summary = "Get current user", description = "Retrieves the profile of the currently authenticated user")
    public ResponseEntity<UserResponse> getCurrentUser(Authentication authentication) {
        UserDetails userDetails = (UserDetails) authentication.getPrincipal();
        UserResponse response = userService.getCurrentUser(userDetails.getUsername());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/me/github-connection")
    @Operation(summary = "Get GitHub connection status", description = "Retrieves the GitHub access token if user logged in via GitHub OAuth")
    public ResponseEntity<Map<String, Object>> getGitHubConnection(Authentication authentication) {
        UserDetails userDetails = (UserDetails) authentication.getPrincipal();
        User user = userRepository.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new RuntimeException("User not found"));

        Map<String, Object> response = new HashMap<>();

        boolean isGithubUser = "github".equalsIgnoreCase(user.getProvider());
        boolean hasToken = user.getGithubAccessToken() != null && !user.getGithubAccessToken().isBlank();

        response.put("connected", isGithubUser && hasToken);
        response.put("provider", user.getProvider());

        if (isGithubUser && hasToken) {
            response.put("accessToken", user.getGithubAccessToken());
            response.put("avatarUrl", user.getAvatarUrl());
            response.put("username", user.getFullName());
        }

        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/me/github-connection")
    @Operation(summary = "Disconnect GitHub", description = "Removes the stored GitHub access token")
    public ResponseEntity<Void> disconnectGitHub(Authentication authentication) {
        UserDetails userDetails = (UserDetails) authentication.getPrincipal();
        User user = userRepository.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new RuntimeException("User not found"));

        user.setGithubAccessToken(null);
        userRepository.save(user);

        return ResponseEntity.noContent().build();
    }
}
