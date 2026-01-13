package ai.mindvex.backend.service;

import ai.mindvex.backend.dto.WorkspaceRequest;
import ai.mindvex.backend.dto.WorkspaceResponse;
import ai.mindvex.backend.entity.Workspace;
import ai.mindvex.backend.exception.ResourceNotFoundException;
import ai.mindvex.backend.exception.UnauthorizedException;
import ai.mindvex.backend.repository.WorkspaceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class WorkspaceService {

    private final WorkspaceRepository workspaceRepository;

    @Transactional
    public WorkspaceResponse createWorkspace(WorkspaceRequest request, Long userId) {
        log.info("Creating workspace for user: {}", userId);

        Workspace workspace = Workspace.builder()
                .userId(userId)
                .name(request.getName())
                .description(request.getDescription())
                .settings(request.getSettings())
                .build();

        workspace = workspaceRepository.save(workspace);
        log.info("Workspace created with ID: {}", workspace.getId());

        return mapToResponse(workspace);
    }

    @Transactional(readOnly = true)
    public List<WorkspaceResponse> getAllUserWorkspaces(Long userId) {
        log.info("Fetching all workspaces for user: {}", userId);

        List<Workspace> workspaces = workspaceRepository.findByUserId(userId);

        return workspaces.stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public WorkspaceResponse getWorkspaceById(Long id, Long userId) {
        log.info("Fetching workspace {} for user: {}", id, userId);

        Workspace workspace = workspaceRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Workspace not found or access denied"));

        return mapToResponse(workspace);
    }

    @Transactional
    public WorkspaceResponse updateWorkspace(Long id, WorkspaceRequest request, Long userId) {
        log.info("Updating workspace {} for user: {}", id, userId);

        Workspace workspace = workspaceRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Workspace not found or access denied"));

        workspace.setName(request.getName());
        workspace.setDescription(request.getDescription());
        workspace.setSettings(request.getSettings());

        workspace = workspaceRepository.save(workspace);
        log.info("Workspace {} updated successfully", id);

        return mapToResponse(workspace);
    }

    @Transactional
    public void deleteWorkspace(Long id, Long userId) {
        log.info("Deleting workspace {} for user: {}", id, userId);

        Workspace workspace = workspaceRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Workspace not found or access denied"));

        workspaceRepository.delete(workspace);
        log.info("Workspace {} deleted successfully", id);
    }

    private WorkspaceResponse mapToResponse(Workspace workspace) {
        return WorkspaceResponse.builder()
                .id(workspace.getId())
                .userId(workspace.getUserId())
                .name(workspace.getName())
                .description(workspace.getDescription())
                .settings(workspace.getSettings())
                .createdAt(workspace.getCreatedAt())
                .updatedAt(workspace.getUpdatedAt())
                .build();
    }
}
