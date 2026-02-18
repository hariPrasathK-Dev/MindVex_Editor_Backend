package ai.mindvex.backend.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Response payload for GET /api/scip/jobs/{id}.
 */
@Data
@Builder
public class IndexJobResponse {
    private Long id;
    private String repoUrl;
    private String status;
    private String errorMsg;
    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
}
