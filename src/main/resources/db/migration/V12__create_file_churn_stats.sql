-- ============================================================
-- V12: Per-file weekly churn statistics
-- Stores aggregated churn data mined from git history.
-- ============================================================

CREATE TABLE git_analytics.file_churn_stats (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id       BIGINT        NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
    repo_url      VARCHAR(1000) NOT NULL,
    file_path     VARCHAR(2000) NOT NULL,
    week_start    DATE          NOT NULL,   -- Monday of the ISO week
    lines_added   INT           NOT NULL DEFAULT 0,
    lines_deleted INT           NOT NULL DEFAULT 0,
    commit_count  INT           NOT NULL DEFAULT 0,
    churn_rate    NUMERIC(6,2),             -- (added + deleted) / total_lines * 100
    CONSTRAINT uq_file_churn UNIQUE (user_id, repo_url, file_path, week_start)
);

-- Fast hotspot lookup: highest churn files for a given user+repo
CREATE INDEX idx_file_churn_hotspot
    ON git_analytics.file_churn_stats(user_id, repo_url, churn_rate DESC);

-- Fast time-range queries for trend charts
CREATE INDEX idx_file_churn_week
    ON git_analytics.file_churn_stats(user_id, repo_url, file_path, week_start);
