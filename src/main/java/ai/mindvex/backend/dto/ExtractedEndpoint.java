package ai.mindvex.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a cleaned and standardized API endpoint extracted from code.
 * Used as internal structure before final documentation generation.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExtractedEndpoint {
    
    /**
     * HTTP method (GET, POST, PUT, DELETE, PATCH)
     */
    private String method;
    
    /**
     * Full endpoint path including router prefix
     * Example: /auth/login, /posts/{id}, /users/{username}/connect
     */
    private String path;
    
    /**
     * Short professional description of what this endpoint does
     */
    private String description;
    
    /**
     * Whether authentication is required
     */
    private boolean requiresAuth;
    
    /**
     * List of parameters (path, query, header)
     */
    @Builder.Default
    private List<EndpointParameter> parameters = new ArrayList<>();
    
    /**
     * Request body schema (JSON or null for GET requests)
     */
    private String requestBody;
    
    /**
     * Success response schema (JSON)
     */
    private String responseBody;
    
    /**
     * Error responses (400, 401, 404, 500)
     */
    @Builder.Default
    private List<ErrorResponse> errorResponses = new ArrayList<>();
    
    /**
     * Source file where this endpoint was found (for deduplication)
     */
    private String sourceFile;
    
    /**
     * Router prefix if detected (e.g., "/auth", "/api/v1")
     */
    private String routerPrefix;
    
    /**
     * Generate unique key for deduplication
     */
    public String getUniqueKey() {
        return method + ":" + path;
    }
    
    /**
     * Merge information from another endpoint
     * Used when deduplicating to combine details from multiple sources
     */
    public void mergeWith(ExtractedEndpoint other) {
        // Prefer non-empty descriptions
        if (this.description == null || this.description.isBlank()) {
            this.description = other.description;
        }
        
        // Merge parameters (avoid duplicates)
        if (other.parameters != null) {
            for (EndpointParameter param : other.parameters) {
                boolean exists = this.parameters.stream()
                    .anyMatch(p -> p.getName().equals(param.getName()) && 
                                   p.getLocation().equals(param.getLocation()));
                if (!exists) {
                    this.parameters.add(param);
                }
            }
        }
        
        // Prefer non-empty request/response bodies
        if (this.requestBody == null || this.requestBody.isBlank()) {
            this.requestBody = other.requestBody;
        }
        if (this.responseBody == null || this.responseBody.isBlank()) {
            this.responseBody = other.responseBody;
        }
        
        // Merge error responses
        if (other.errorResponses != null) {
            for (ErrorResponse err : other.errorResponses) {
                boolean exists = this.errorResponses.stream()
                    .anyMatch(e -> e.getCode() == err.getCode());
                if (!exists) {
                    this.errorResponses.add(err);
                }
            }
        }
        
        // Set auth requirement if either source says it's required
        this.requiresAuth = this.requiresAuth || other.requiresAuth;
    }
}
