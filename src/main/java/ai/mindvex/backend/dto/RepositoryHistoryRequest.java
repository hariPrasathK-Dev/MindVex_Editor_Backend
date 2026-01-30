package ai.mindvex.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for creating or updating a repository history entry.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RepositoryHistoryRequest {
    
    @NotBlank(message = "Repository URL is required")
    @Size(max = 1000, message = "Repository URL must be at most 1000 characters")
    private String url;
    
    @NotBlank(message = "Repository name is required")
    @Size(max = 255, message = "Repository name must be at most 255 characters")
    private String name;
    
    @Size(max = 5000, message = "Description must be at most 5000 characters")
    private String description;
    
    @Size(max = 255, message = "Branch name must be at most 255 characters")
    private String branch;
    
    @Size(max = 40, message = "Commit hash must be at most 40 characters")
    private String commitHash;
}
