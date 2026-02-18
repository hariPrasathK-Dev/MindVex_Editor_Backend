-- ============================================================
-- V10: SCIP symbol tables in code_intelligence schema
-- Stores parsed SCIP index data: documents, occurrences, symbols
-- ============================================================

-- One row per indexed source file
CREATE TABLE code_intelligence.scip_documents (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id      BIGINT NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
    repo_url     VARCHAR(1000) NOT NULL,
    relative_uri VARCHAR(2000) NOT NULL,
    language     VARCHAR(50),
    indexed_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_scip_document UNIQUE (user_id, repo_url, relative_uri)
);

-- Symbol occurrences within a document (ranges + role flags)
CREATE TABLE code_intelligence.scip_occurrences (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    document_id  BIGINT NOT NULL REFERENCES code_intelligence.scip_documents(id) ON DELETE CASCADE,
    symbol       TEXT NOT NULL,
    start_line   INT NOT NULL,
    start_char   INT NOT NULL,
    end_line     INT NOT NULL,
    end_char     INT NOT NULL,
    role_flags   INT NOT NULL DEFAULT 0
);

-- Symbol metadata: type signatures and documentation
CREATE TABLE code_intelligence.scip_symbols (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id        BIGINT NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
    repo_url       VARCHAR(1000) NOT NULL,
    symbol         TEXT NOT NULL,
    display_name   TEXT,
    signature_doc  TEXT,
    documentation  TEXT,
    CONSTRAINT uq_scip_symbol UNIQUE (user_id, repo_url, symbol)
);

CREATE INDEX idx_scip_docs_user_repo ON code_intelligence.scip_documents(user_id, repo_url);
CREATE INDEX idx_scip_occ_document   ON code_intelligence.scip_occurrences(document_id);
CREATE INDEX idx_scip_occ_symbol     ON code_intelligence.scip_occurrences(symbol);
CREATE INDEX idx_scip_sym_lookup     ON code_intelligence.scip_symbols(user_id, repo_url, symbol);
