package ai.mindvex.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Response DTO for repository history entries.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RepositoryHistoryResponse {

    private Long id;
    private String url;
    private String name;
    private String description;
    private String branch;
    private String commitHash;
    private LocalDateTime createdAt;
    private LocalDateTime lastAccessedAt;
}
