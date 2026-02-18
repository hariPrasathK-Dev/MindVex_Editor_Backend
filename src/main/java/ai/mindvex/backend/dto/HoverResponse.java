package ai.mindvex.backend.dto;

import lombok.Builder;
import lombok.Data;

/**
 * Response payload for the GET /api/scip/hover endpoint.
 */
@Data
@Builder
public class HoverResponse {
    private String symbol;
    private String displayName;
    private String signatureDoc;
    private String documentation;
    private int startLine;
    private int startChar;
    private int endLine;
    private int endChar;
}
