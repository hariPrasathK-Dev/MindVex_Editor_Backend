-- V15: Create the vector_embeddings table for semantic code search.
--
-- pgvector extension must already be enabled on the target database.
-- On Railway: enable via the Railway dashboard or SQL console first.
-- The embedding column uses TEXT type for JPA compatibility.
-- Native pgvector queries (cosine distance) will cast at query time.

CREATE SCHEMA IF NOT EXISTS code_intelligence;

CREATE TABLE IF NOT EXISTS code_intelligence.vector_embeddings (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id           BIGINT          NOT NULL,
    repo_url          VARCHAR(1000)   NOT NULL,
    file_path         VARCHAR(2000)   NOT NULL,
    chunk_index       INT             NOT NULL DEFAULT 0,
    chunk_text        TEXT            NOT NULL,
    embedding         TEXT,           -- stored as "[0.1,0.2,...]", cast to vector at query time
    created_at        TIMESTAMP       NOT NULL DEFAULT now(),

    CONSTRAINT uq_embedding_chunk UNIQUE (user_id, repo_url, file_path, chunk_index)
);
