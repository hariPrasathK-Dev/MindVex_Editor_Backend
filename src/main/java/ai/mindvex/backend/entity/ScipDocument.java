package ai.mindvex.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * Represents one indexed source file within a SCIP index upload.
 */
@Entity
@Table(name = "scip_documents", schema = "code_intelligence", uniqueConstraints = @UniqueConstraint(name = "uq_scip_document", columnNames = {
        "user_id", "repo_url", "relative_uri" }))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class ScipDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "repo_url", nullable = false, length = 1000)
    private String repoUrl;

    @Column(name = "relative_uri", nullable = false, length = 2000)
    private String relativeUri;

    @Column(name = "language", length = 50)
    private String language;

    @CreatedDate
    @Column(name = "indexed_at", nullable = false, updatable = false)
    private LocalDateTime indexedAt;
}
