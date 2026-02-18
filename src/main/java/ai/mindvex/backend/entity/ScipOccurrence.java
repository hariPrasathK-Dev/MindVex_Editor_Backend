package ai.mindvex.backend.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * A symbol occurrence within a SCIP-indexed document.
 * Stores the source range and role flags as defined by the SCIP spec.
 */
@Entity
@Table(name = "scip_occurrences", schema = "code_intelligence")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScipOccurrence {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "document_id", nullable = false)
    private Long documentId;

    @Column(name = "symbol", nullable = false, columnDefinition = "TEXT")
    private String symbol;

    @Column(name = "start_line", nullable = false)
    private int startLine;

    @Column(name = "start_char", nullable = false)
    private int startChar;

    @Column(name = "end_line", nullable = false)
    private int endLine;

    @Column(name = "end_char", nullable = false)
    private int endChar;

    /**
     * Bitmask of SCIP SymbolRole values.
     * 1 = Definition, 2 = Import, 4 = WriteAccess, 8 = ReadAccess, etc.
     */
    @Column(name = "role_flags", nullable = false)
    @Builder.Default
    private int roleFlags = 0;
}
