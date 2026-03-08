package ai.mindvex.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO representing an edge/link in the dependency graph
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GraphEdgeDto {
    
    /**
     * Source node ID
     */
    private String source;
    
    /**
     * Target node ID
     */
    private String target;
    
    /**
     * Edge type (e.g., "import", "extends", "implements", "calls")
     */
    private String type;
    
    /**
     * Weight/strength of the relationship
     */
    private int weight;
    
    /**
     * Optional label for the edge
     */
    private String label;
}
