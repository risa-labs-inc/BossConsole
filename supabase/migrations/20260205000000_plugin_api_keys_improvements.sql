-- ============================================================================
-- BOSS Database Schema: Plugin API Keys Improvements
-- ============================================================================
-- File: 20260205000000_plugin_api_keys_improvements.sql
-- Description: Security and performance improvements for plugin API keys
-- Dependencies:
--   - 20260204000000_plugin_store_api_keys.sql
-- ============================================================================


-- ============================================================================
-- SECTION 1: Additional Indexes
-- ============================================================================

-- Index 1.1: Unique constraint on key_hash
-- -----------------------------------------------------------------------------
-- Purpose: Prevent hash collisions (cryptographically unlikely but defense-in-depth)
-- Security: Ensures no two API keys can have the same hash
-- -----------------------------------------------------------------------------
CREATE UNIQUE INDEX IF NOT EXISTS idx_plugin_api_keys_key_hash_unique 
    ON plugin_api_keys(key_hash);

COMMENT ON INDEX idx_plugin_api_keys_key_hash_unique IS 
    'Unique constraint on key hash - prevents collisions';


-- Index 1.2: Partial index on expires_at for active keys
-- -----------------------------------------------------------------------------
-- Purpose: Optimize expiration checks for active (non-revoked) keys
-- Performance: Speeds up queries that check for expired keys
-- -----------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_plugin_api_keys_expires_at 
    ON plugin_api_keys(expires_at) 
    WHERE revoked_at IS NULL;

COMMENT ON INDEX idx_plugin_api_keys_expires_at IS 
    'Partial index on expires_at for active keys - optimizes expiration checks';


-- ============================================================================
-- SECTION 2: Rate Limiting (Max 10 API Keys per User)
-- ============================================================================

-- Function 2.1: Check API key limit
-- -----------------------------------------------------------------------------
-- Purpose: Prevent users from creating unlimited API keys
-- Security: Limits potential abuse for reconnaissance or resource exhaustion
-- Limit: 10 active (non-revoked) keys per user (default)
--
-- NOTE: This SQL limit acts as defense-in-depth. The primary limit is enforced
-- in the Edge Function via MAX_API_KEYS_PER_USER environment variable.
-- To change this SQL limit, modify max_keys_per_user below and redeploy.
-- -----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION check_api_key_limit()
RETURNS TRIGGER AS $$
DECLARE
    active_key_count INTEGER;
    max_keys_per_user CONSTANT INTEGER := 10;
BEGIN
    -- Count active (non-revoked) keys for this user
    SELECT COUNT(*) INTO active_key_count
    FROM plugin_api_keys
    WHERE user_id = NEW.user_id
    AND revoked_at IS NULL;
    
    -- Check limit (count is before insert, so >= means we'd exceed)
    IF active_key_count >= max_keys_per_user THEN
        RAISE EXCEPTION 'API key limit exceeded. Maximum % active keys per user allowed.', max_keys_per_user
            USING ERRCODE = 'check_violation';
    END IF;
    
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION check_api_key_limit IS 
    'Enforce maximum 10 active API keys per user';


-- Trigger 2.2: Enforce API key limit on insert
-- -----------------------------------------------------------------------------
CREATE OR REPLACE TRIGGER trigger_check_api_key_limit
    BEFORE INSERT ON plugin_api_keys
    FOR EACH ROW
    EXECUTE FUNCTION check_api_key_limit();

COMMENT ON TRIGGER trigger_check_api_key_limit ON plugin_api_keys IS 
    'Enforce max 10 active API keys per user on insert';


-- Function 2.3: Get user's active API key count
-- -----------------------------------------------------------------------------
-- Purpose: Allow Edge Functions to check count before attempting insert
-- Returns: Number of active (non-revoked) keys for the user
-- -----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION get_user_api_key_count(p_user_id UUID)
RETURNS INTEGER AS $$
DECLARE
    key_count INTEGER;
BEGIN
    SELECT COUNT(*) INTO key_count
    FROM plugin_api_keys
    WHERE user_id = p_user_id
    AND revoked_at IS NULL;
    
    RETURN key_count;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

COMMENT ON FUNCTION get_user_api_key_count IS 
    'Get count of active API keys for a user (for rate limit checks)';


-- ============================================================================
-- SECTION 3: Grants
-- ============================================================================

-- Grant execute permission on the count function to service_role
GRANT EXECUTE ON FUNCTION get_user_api_key_count TO service_role;
