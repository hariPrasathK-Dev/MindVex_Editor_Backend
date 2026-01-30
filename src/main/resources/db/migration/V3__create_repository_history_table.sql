-- Create repository_history table for storing user's imported repositories
CREATE TABLE repository_history (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    url VARCHAR(1000) NOT NULL,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    branch VARCHAR(255),
    commit_hash VARCHAR(40),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    last_accessed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT uk_repository_history_user_url UNIQUE (user_id, url)
);

-- Create index for faster user-based queries
CREATE INDEX idx_repository_history_user_id ON repository_history(user_id);

-- Create index for sorting by last accessed
CREATE INDEX idx_repository_history_last_accessed ON repository_history(last_accessed_at DESC);

-- Add comment to table
COMMENT ON TABLE repository_history IS 'Stores imported repository history for each user, max 50 per user';
