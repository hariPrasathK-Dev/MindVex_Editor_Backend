-- ============================================================
-- V11: Async index job queue (LSF-Worker pattern)
-- Jobs are polled with SELECT ... FOR UPDATE SKIP LOCKED
-- to safely support concurrent worker instances.
-- ============================================================

CREATE TABLE public.index_jobs (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id      BIGINT NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
    repo_url     VARCHAR(1000) NOT NULL,
    status       VARCHAR(20) NOT NULL DEFAULT 'pending'
                     CHECK (status IN ('pending', 'processing', 'done', 'failed')),
    payload_path TEXT,
    error_msg    TEXT,
    created_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at   TIMESTAMP,
    finished_at  TIMESTAMP
);

CREATE INDEX idx_index_jobs_status ON public.index_jobs(status, created_at);
