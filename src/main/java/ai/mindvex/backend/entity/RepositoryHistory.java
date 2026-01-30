package ai.mindvex.backend.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * Entity representing a repository import history entry for a user.
 * Tracks repositories that have been cloned to the workbench.
 */
@Entity
@Table(name = "repository_history")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class RepositoryHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @NotBlank(message = "Repository URL is required")
    @Size(max = 1000, message = "Repository URL must be at most 1000 characters")
    @Column(nullable = false, length = 1000)
    private String url;

    @NotBlank(message = "Repository name is required")
    @Size(max = 255, message = "Repository name must be at most 255 characters")
    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Size(max = 255, message = "Branch name must be at most 255 characters")
    private String branch;

    @Size(max = 40, message = "Commit hash must be at most 40 characters")
    @Column(name = "commit_hash", length = 40)
    private String commitHash;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "last_accessed_at", nullable = false)
    private LocalDateTime lastAccessedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", insertable = false, updatable = false)
    private User user;
}
