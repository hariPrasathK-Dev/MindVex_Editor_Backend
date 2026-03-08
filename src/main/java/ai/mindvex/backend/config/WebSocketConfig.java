package ai.mindvex.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * WebSocket Configuration for Real-Time Graph Updates
 * 
 * Configures STOMP messaging over WebSocket with SockJS fallback support.
 * Enables real-time streaming of dependency graph updates to connected clients.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    /**
     * Configure message broker for pub/sub messaging.
     * - /topic: Simple in-memory broker for broadcasting to subscribers
     * - /app: Application destination prefix for client-to-server messages
     */
    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // Enable simple in-memory message broker for /topic destinations
        config.enableSimpleBroker("/topic");
        
        // Set application destination prefix for @MessageMapping
        config.setApplicationDestinationPrefixes("/app");
    }

    /**
     * Register STOMP endpoints for WebSocket connections.
     * - /ws-graph: Primary WebSocket endpoint with SockJS fallback
     * - Allowed origins configured for CORS support
     */
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws-graph")
                .setAllowedOriginPatterns("*") // Configure based on environment
                .withSockJS(); // Enable SockJS fallback for browsers without WebSocket support
    }
}
