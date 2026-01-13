package ai.mindvex.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;

@Data
public class WorkspaceRequest {

    @NotBlank(message = "Workspace name is required")
    @Size(min = 1, max = 255, message = "Workspace name must be between 1 and 255 characters")
    private String name;

    @Size(max = 1000, message = "Description must not exceed 1000 characters")
    private String description;

    private Map<String, Object> settings = new HashMap<>();
}
