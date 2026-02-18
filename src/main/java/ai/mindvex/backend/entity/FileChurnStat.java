package ai.mindvex.backend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * JPA entity for git_analytics.file_churn_stats (created in V12).
 */
@Entity
@Table(schema = "git_analytics", name = "file_churn_stats")
@Getter
@Setter
public class FileChurnStat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "repo_url", nullable = false, length = 1000)
    private String repoUrl;

    @Column(name = "file_path", nullable = false, length = 2000)
    private String filePath;

    @Column(name = "week_start", nullable = false)
    private LocalDate weekStart;

    @Column(name = "lines_added", nullable = false)
    private int linesAdded;

    @Column(name = "lines_deleted", nullable = false)
    private int linesDeleted;

    @Column(name = "commit_count", nullable = false)
    private int commitCount;

    @Column(name = "churn_rate", precision = 6, scale = 2)
    private BigDecimal churnRate;
}
