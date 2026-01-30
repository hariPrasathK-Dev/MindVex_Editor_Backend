-- Fix Flyway schema history after failed migration
-- Run this SQL script in your PostgreSQL database

-- Step 1: Check the current Flyway schema history
SELECT * FROM flyway_schema_history ORDER BY installed_rank;

-- Step 2: Delete the failed migration entry (if it exists)
DELETE FROM flyway_schema_history WHERE version = '2' AND success = false;

-- Step 3: Verify the deletion
SELECT * FROM flyway_schema_history ORDER BY installed_rank;
