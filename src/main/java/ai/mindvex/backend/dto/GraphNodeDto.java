package ai.mindvex.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO representing a node in the dependency graph
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GraphNodeDto {
    
    /**
     * Unique identifier for the node (typically file path)
     */
    private String id;
    
    /**
     * Display label for the node
     */
    private String label;
    
    /**
     * File type/extension (e.g., "java", "tsx", "py")
     */
    private String fileType;
    
    /**
     * Number of dependencies (outgoing edges)
     */
    private int dependencies;
    
    /**
     * Number of dependents (incoming edges)
     */
    private int dependents;
    
    /**
     * Category/group for visualization (e.g., "controller", "service", "model")
     */
    private String group;
    
    /**
     * Size/weight for visualization
     */
    private int size;
    
    /**
     * Optional metadata
     */
    private NodeMetadata metadata;
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NodeMetadata {
        private String fullPath;
        private long fileSize;
        private int linesOfCode;
        private String lastModified;
    }
}
