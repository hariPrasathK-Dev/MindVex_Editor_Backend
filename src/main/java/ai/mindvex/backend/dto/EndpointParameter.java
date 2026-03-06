package ai.mindvex.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a parameter for an API endpoint.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EndpointParameter {
    
    /**
     * Parameter name
     */
    private String name;
    
    /**
     * Parameter type (string, integer, boolean, object, array)
     */
    private String type;
    
    /**
     * Location (path, query, header, body)
     */
    private String location;
    
    /**
     * Whether this parameter is required
     */
    private boolean required;
    
    /**
     * Description of what this parameter does
     */
    private String description;
    
    /**
     * Example value
     */
    private String example;
}
