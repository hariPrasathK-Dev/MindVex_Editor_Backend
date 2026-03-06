package ai.mindvex.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a cleaned README section extracted from code/docs.
 * Used for deduplicating and standardizing README content.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExtractedReadmeSection {
    
    /**
     * Section type (features, installation, usage, configuration, tech_stack)
     */
    private String sectionType;
    
    /**
     * Section title/heading
     */
    private String title;
    
    /**
     * Cleaned and standardized content
     */
    private String content;
    
    /**
     * Commands found in this section (preserve as-is)
     */
    private java.util.List<String> commands;
    
    /**
     * Environment variables found (preserve as-is)
     */
    private java.util.List<String> environmentVariables;
    
    /**
     * Code snippets found (preserve as-is)
     */
    private java.util.List<String> codeSnippets;
    
    /**
     * Source where this section was found
     */
    private String source;
    
    /**
     * Priority/confidence level (higher = more reliable source)
     */
    private int priority;
    
    /**
     * Whether this section is complete or a fragment
     */
    private boolean isComplete;
    
    /**
     * Merge with another section (for deduplication)
     */
    public void mergeWith(ExtractedReadmeSection other) {
        // Prefer complete sections over fragments
        if (!this.isComplete && other.isComplete) {
            this.content = other.content;
            this.isComplete = true;
        }
        
        // Prefer higher priority sources
        if (other.priority > this.priority) {
            this.content = other.content;
            this.priority = other.priority;
        }
        
        // Merge commands (avoid duplicates)
        if (other.commands != null) {
            for (String cmd : other.commands) {
                if (this.commands != null && !this.commands.contains(cmd)) {
                    this.commands.add(cmd);
                }
            }
        }
        
        // Merge environment variables
        if (other.environmentVariables != null) {
            for (String env : other.environmentVariables) {
                if (this.environmentVariables != null && !this.environmentVariables.contains(env)) {
                    this.environmentVariables.add(env);
                }
            }
        }
        
        // Merge code snippets
        if (other.codeSnippets != null) {
            for (String snippet : other.codeSnippets) {
                if (this.codeSnippets != null && !this.codeSnippets.contains(snippet)) {
                    this.codeSnippets.add(snippet);
                }
            }
        }
    }
}
