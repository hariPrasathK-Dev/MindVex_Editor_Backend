package ai.mindvex.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class WorkspaceResponse {

    private Long id;
    private Long userId;
    private String name;
    private String description;
    private Map<String, Object> settings;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
