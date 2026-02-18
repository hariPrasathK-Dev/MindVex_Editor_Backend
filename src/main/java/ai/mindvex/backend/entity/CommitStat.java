package ai.mindvex.backend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * JPA entity for git_analytics.commit_stats (created in V8).
 */
@Entity
@Table(schema = "git_analytics", name = "commit_stats")
@Getter
@Setter
public class CommitStat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "repo_url", nullable = false, length = 1000)
    private String repoUrl;

    @Column(name = "commit_hash", nullable = false, length = 40)
    private String commitHash;

    @Column(name = "author_email", length = 255)
    private String authorEmail;

    @Column(name = "message", columnDefinition = "TEXT")
    private String message;

    @Column(name = "committed_at")
    private Instant committedAt;

    @Column(name = "files_changed")
    private Integer filesChanged;

    @Column(name = "insertions")
    private Integer insertions;

    @Column(name = "deletions")
    private Integer deletions;

    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt = Instant.now();
}
