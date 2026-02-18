package ai.mindvex.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * Represents an async background job (SCIP indexing or git mining).
 * Workers poll this table using SELECT ... FOR UPDATE SKIP LOCKED.
 * jobType: 'scip_index' | 'git_mine'
 */
@Entity
@Table(name = "index_jobs")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class IndexJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "repo_url", nullable = false, length = 1000)
    private String repoUrl;

    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "pending";

    /** Discriminator: 'scip_index' or 'git_mine' */
    @Column(name = "job_type", length = 30)
    private String jobType;

    /** Temp file path where the uploaded .scip binary is stored. */
    @Column(name = "payload_path", columnDefinition = "TEXT")
    private String payloadPath;

    /** JSON metadata for the job (e.g. {"days":90} for git_mine). */
    @Column(name = "payload", columnDefinition = "TEXT")
    private String payload;

    @Column(name = "error_msg", columnDefinition = "TEXT")
    private String errorMsg;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;
}
