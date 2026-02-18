-- ============================================================
-- V8: Create git_analytics schema
-- Stores per-repo commit metadata fetched via GitHub API.
-- ============================================================

CREATE SCHEMA IF NOT EXISTS git_analytics;

CREATE TABLE git_analytics.commit_stats (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id       BIGINT NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
    repo_url      VARCHAR(1000) NOT NULL,
    commit_hash   VARCHAR(40) NOT NULL,
    author_email  VARCHAR(255),
    message       TEXT,
    committed_at  TIMESTAMP,
    files_changed INT,
    insertions    INT,
    deletions     INT,
    recorded_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_commit_stats UNIQUE (user_id, repo_url, commit_hash)
);

CREATE INDEX idx_commit_stats_user_repo
    ON git_analytics.commit_stats(user_id, repo_url);
