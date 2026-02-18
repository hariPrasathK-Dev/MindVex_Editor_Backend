package ai.mindvex.backend.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Symbol metadata extracted from a SCIP index.
 * Stores display name, type signature, and documentation for hover queries.
 */
@Entity
@Table(name = "scip_symbols", schema = "code_intelligence", uniqueConstraints = @UniqueConstraint(name = "uq_scip_symbol", columnNames = {
        "user_id", "repo_url", "symbol" }))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScipSymbolInfo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "repo_url", nullable = false, length = 1000)
    private String repoUrl;

    @Column(name = "symbol", nullable = false, columnDefinition = "TEXT")
    private String symbol;

    @Column(name = "display_name", columnDefinition = "TEXT")
    private String displayName;

    @Column(name = "signature_doc", columnDefinition = "TEXT")
    private String signatureDoc;

    @Column(name = "documentation", columnDefinition = "TEXT")
    private String documentation;
}
