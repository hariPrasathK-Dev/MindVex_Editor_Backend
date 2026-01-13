package ai.mindvex.backend.controller;

import ai.mindvex.backend.dto.WorkspaceRequest;
import ai.mindvex.backend.dto.WorkspaceResponse;
import ai.mindvex.backend.service.WorkspaceService;
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

@RestController
@RequestMapping("/api/workspaces")
@RequiredArgsConstructor
@Tag(name = "Workspaces", description = "Workspace management endpoints")
@SecurityRequirement(name = "Bearer Authentication")
public class WorkspaceController {

    private final WorkspaceService workspaceService;
    private final ai.mindvex.backend.repository.UserRepository userRepository;

    @PostMapping
    @Operation(summary = "Create a new workspace", description = "Creates a new workspace for the authenticated user")
    public ResponseEntity<WorkspaceResponse> createWorkspace(
            @Valid @RequestBody WorkspaceRequest request,
            Authentication authentication) {

        Long userId = getUserIdFromAuthentication(authentication);
        WorkspaceResponse response = workspaceService.createWorkspace(request, userId);
        return new ResponseEntity<>(response, HttpStatus.CREATED);
    }

    @GetMapping
    @Operation(summary = "Get all workspaces", description = "Retrieves all workspaces for the authenticated user")
    public ResponseEntity<List<WorkspaceResponse>> getAllWorkspaces(Authentication authentication) {
        Long userId = getUserIdFromAuthentication(authentication);
        List<WorkspaceResponse> workspaces = workspaceService.getAllUserWorkspaces(userId);
        return ResponseEntity.ok(workspaces);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get workspace by ID", description = "Retrieves a specific workspace by ID")
    public ResponseEntity<WorkspaceResponse> getWorkspaceById(
            @PathVariable Long id,
            Authentication authentication) {

        Long userId = getUserIdFromAuthentication(authentication);
        WorkspaceResponse response = workspaceService.getWorkspaceById(id, userId);
        return ResponseEntity.ok(response);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update workspace", description = "Updates an existing workspace")
    public ResponseEntity<WorkspaceResponse> updateWorkspace(
            @PathVariable Long id,
            @Valid @RequestBody WorkspaceRequest request,
            Authentication authentication) {

        Long userId = getUserIdFromAuthentication(authentication);
        WorkspaceResponse response = workspaceService.updateWorkspace(id, request, userId);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete workspace", description = "Deletes a workspace and all associated data")
    public ResponseEntity<Void> deleteWorkspace(
            @PathVariable Long id,
            Authentication authentication) {

        Long userId = getUserIdFromAuthentication(authentication);
        workspaceService.deleteWorkspace(id, userId);
        return ResponseEntity.noContent().build();
    }

    private Long getUserIdFromAuthentication(Authentication authentication) {
        UserDetails userDetails = (UserDetails) authentication.getPrincipal();
        return userRepository.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new RuntimeException("User not found"))
                .getId();
    }
}
