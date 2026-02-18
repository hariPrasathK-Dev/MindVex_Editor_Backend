-- ============================================================
-- V13: Add job_type and payload columns to index_jobs
-- Allows the same job queue to handle both SCIP indexing and
-- git history mining jobs.
-- ============================================================

ALTER TABLE public.index_jobs
    ADD COLUMN IF NOT EXISTS job_type VARCHAR(30) DEFAULT 'scip_index',
    ADD COLUMN IF NOT EXISTS payload  TEXT;
