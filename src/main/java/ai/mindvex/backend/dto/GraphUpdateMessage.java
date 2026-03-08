package ai.mindvex.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * DTO for real-time graph update messages sent via WebSocket
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GraphUpdateMessage {
    
    /**
     * Type of update: 'node_added', 'edge_added', 'batch_update', 'complete', 'heartbeat'
     */
    private String type;
    
    /**
     * Repository ID or URL identifier
     */
    private String repoId;
    
    /**
     * List of nodes to add (if type is node_added or batch_update)
     */
    private List<GraphNodeDto> nodes;
    
    /**
     * List of edges/links to add (if type is edge_added or batch_update)
     */
    private List<GraphEdgeDto> edges;
    
    /**
     * Optional metadata about the update
     */
    private UpdateMetadata metadata;
    
    /**
     * Timestamp of the update
     */
    private long timestamp;
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpdateMetadata {
        private int totalNodes;
        private int totalEdges;
        private int processedFiles;
        private String status;
        private String message;
    }
}
