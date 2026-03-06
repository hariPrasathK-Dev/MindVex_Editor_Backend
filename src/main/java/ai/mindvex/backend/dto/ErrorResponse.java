package ai.mindvex.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents an error response for an API endpoint.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ErrorResponse {
    
    /**
     * HTTP status code (400, 401, 404, 500, etc.)
     */
    private int code;
    
    /**
     * Error name (Bad Request, Unauthorized, Not Found, etc.)
     */
    private String name;
    
    /**
     * Description of when this error occurs
     */
    private String description;
    
    /**
     * Example error response JSON
     */
    private String example;
}
