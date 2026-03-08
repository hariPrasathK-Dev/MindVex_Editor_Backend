package ai.mindvex.backend.controller;

import ai.mindvex.backend.dto.GraphUpdateMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.CrossOrigin;

/**
 * WebSocket Controller for Real-Time Graph Updates
 * 
 * Handles WebSocket connections and messaging for live dependency graph streaming.
 * Clients can subscribe to /topic/graph-updates/{repoId} to receive real-time updates.
 */
@Controller
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class WebSocketGraphController {

    private final SimpMessagingTemplate messagingTemplate;

    /**
     * Handle client subscription message
     * Client sends to: /app/graph/subscribe/{repoId}
     * Response broadcasts to: /topic/graph-updates/{repoId}
     */
    @MessageMapping("/graph/subscribe/{repoId}")
    @SendTo("/topic/graph-updates/{repoId}")
    public GraphUpdateMessage handleSubscription(@DestinationVariable String repoId) {
        log.info("Client subscribed to graph updates for repo: {}", repoId);
        
        return GraphUpdateMessage.builder()
                .type("subscription_confirmed")
                .repoId(repoId)
                .timestamp(System.currentTimeMillis())
                .metadata(GraphUpdateMessage.UpdateMetadata.builder()
                        .status("connected")
                        .message("WebSocket connection established")
                        .build())
                .build();
    }

    /**
     * Handle heartbeat/ping messages from clients
     * Client sends to: /app/graph/ping
     * Response broadcasts to sender
     */
    @MessageMapping("/graph/ping")
    @SendTo("/topic/graph-heartbeat")
    public GraphUpdateMessage handlePing() {
        return GraphUpdateMessage.builder()
                .type("heartbeat")
                .timestamp(System.currentTimeMillis())
                .build();
    }

    /**
     * Broadcast graph update to all subscribers of a specific repository
     * This method is called by GraphJobWorker or other services
     * 
     * @param repoId Repository identifier
     * @param message Update message containing nodes/edges
     */
    public void broadcastGraphUpdate(String repoId, GraphUpdateMessage message) {
        String destination = "/topic/graph-updates/" + repoId;
        log.debug("Broadcasting graph update to {}: type={}, nodes={}, edges={}", 
                destination, 
                message.getType(),
                message.getNodes() != null ? message.getNodes().size() : 0,
                message.getEdges() != null ? message.getEdges().size() : 0);
        
        messagingTemplate.convertAndSend(destination, message);
    }

    /**
     * Broadcast heartbeat to all connected clients
     */
    public void broadcastHeartbeat() {
        GraphUpdateMessage heartbeat = GraphUpdateMessage.builder()
                .type("heartbeat")
                .timestamp(System.currentTimeMillis())
                .build();
        
        messagingTemplate.convertAndSend("/topic/graph-heartbeat", heartbeat);
    }

    /**
     * Send completion notification when graph building is done
     * 
     * @param repoId Repository identifier
     * @param totalNodes Total number of nodes in the graph
     * @param totalEdges Total number of edges in the graph
     */
    public void sendCompletionNotification(String repoId, int totalNodes, int totalEdges) {
        GraphUpdateMessage completion = GraphUpdateMessage.builder()
                .type("complete")
                .repoId(repoId)
                .timestamp(System.currentTimeMillis())
                .metadata(GraphUpdateMessage.UpdateMetadata.builder()
                        .totalNodes(totalNodes)
                        .totalEdges(totalEdges)
                        .status("completed")
                        .message("Graph building completed successfully")
                        .build())
                .build();
        
        broadcastGraphUpdate(repoId, completion);
    }
}
