-- Add github_access_token column to users table for storing OAuth access token
ALTER TABLE users ADD COLUMN github_access_token VARCHAR(500);

-- Add index for faster lookups by provider
CREATE INDEX IF NOT EXISTS idx_users_provider ON users(provider);

-- Add comment
COMMENT ON COLUMN users.github_access_token IS 'Stores the GitHub OAuth access token for API operations';
