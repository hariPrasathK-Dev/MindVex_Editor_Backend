-- ============================================================
-- V7: Create code_intelligence schema
-- Stores AST / parse results for repository files.
-- ============================================================

CREATE SCHEMA IF NOT EXISTS code_intelligence;

CREATE TABLE code_intelligence.parsed_files (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id     BIGINT NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
    repo_url    VARCHAR(1000) NOT NULL,
    file_path   VARCHAR(2000) NOT NULL,
    language    VARCHAR(50),
    ast_json    JSONB,
    parsed_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_parsed_files UNIQUE (user_id, repo_url, file_path)
);

CREATE INDEX idx_parsed_files_user_repo
    ON code_intelligence.parsed_files(user_id, repo_url);
