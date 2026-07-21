-- ============================================================================
-- BOSS Database Schema: Plugin Store API Keys
-- ============================================================================
-- File: 20260204000000_plugin_store_api_keys.sql
-- Description: API key tables for plugin publishing via CI/CD.
--              Enables plugin authors to publish versions from GitHub Actions
--              without interactive authentication.
-- Dependencies:
--   - File 1: extensions_and_types.sql (uuid-ossp, pgcrypto)
--   - File: 20260130000000_plugin_store_tables.sql (plugin store tables)
-- Tables: 2 tables
-- ============================================================================


-- ============================================================================
-- API Key Authentication Overview
-- ============================================================================
--
-- This schema implements API key authentication for CI/CD publishing:
--   1. Key format: boss_pk_<32-random-chars> (40 chars total)
--   2. Keys are hashed (SHA-256) before storage - never stored plaintext
--   3. Scopes limit what actions a key can perform
--   4. API keys CANNOT have admin access (isAdmin always false)
--   5. Audit logging tracks all API key usage
--
-- Table Relationships:
--   plugin_api_keys (N) ←→ (1) auth.users
--   plugin_api_key_logs (N) ←→ (1) plugin_api_keys
--
-- ============================================================================


-- ============================================================================
-- SECTION 1: API Key Tables
-- ============================================================================

-- Table 1.1: plugin_api_keys
-- -----------------------------------------------------------------------------
-- Purpose: Store hashed API keys for plugin publishing
-- Why Needed: Enable CI/CD systems to publish without interactive auth
--
-- Security:
--   - key_hash: SHA-256 hash of the full key (never stored plaintext)
--   - key_prefix: First 12 chars for display/identification
--   - scopes: Limited to publish, version, finalize (no admin access)
--   - expires_at: Optional expiration for time-limited keys
--   - revoked_at: Soft delete for revoked keys (audit trail)
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS plugin_api_keys (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    key_prefix TEXT NOT NULL,           -- "boss_pk_" + first 8 random chars (for display)
    key_hash TEXT NOT NULL,             -- SHA-256 hash of full key
    scopes TEXT[] DEFAULT '{publish,version,finalize}',
    created_at TIMESTAMPTZ DEFAULT NOW(),
    last_used_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ,             -- NULL = never expires
    revoked_at TIMESTAMPTZ,             -- NULL = active, NOT NULL = revoked
    UNIQUE(user_id, name)
);

COMMENT ON TABLE plugin_api_keys IS 'Plugin store: API keys for CI/CD publishing';
COMMENT ON COLUMN plugin_api_keys.key_prefix IS 'First 12 chars (boss_pk_ + 8 random) for identification';
COMMENT ON COLUMN plugin_api_keys.key_hash IS 'SHA-256 hash of full key - never store plaintext';
COMMENT ON COLUMN plugin_api_keys.scopes IS 'Allowed actions: publish, version, finalize';
COMMENT ON COLUMN plugin_api_keys.expires_at IS 'NULL = never expires';
COMMENT ON COLUMN plugin_api_keys.revoked_at IS 'NULL = active, timestamp = revoked';


-- Table 1.2: plugin_api_key_logs
-- -----------------------------------------------------------------------------
-- Purpose: Audit trail for API key usage
-- Why Needed: Security compliance and debugging
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS plugin_api_key_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    api_key_id UUID REFERENCES plugin_api_keys(id) ON DELETE CASCADE,
    action TEXT NOT NULL,               -- e.g., 'publish', 'version', 'finalize'
    plugin_id TEXT,                     -- The plugin being acted on
    ip_address TEXT,                    -- For security auditing
    user_agent TEXT,                    -- For debugging CI/CD issues
    success BOOLEAN DEFAULT TRUE,
    error_message TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

COMMENT ON TABLE plugin_api_key_logs IS 'Plugin store: Audit log for API key usage';
COMMENT ON COLUMN plugin_api_key_logs.action IS 'Action performed: publish, version, finalize';
COMMENT ON COLUMN plugin_api_key_logs.plugin_id IS 'Plugin ID string being acted on';


-- ============================================================================
-- SECTION 2: Indexes
-- ============================================================================

-- Fast lookup by key hash (used on every API request)
CREATE INDEX IF NOT EXISTS idx_plugin_api_keys_key_hash ON plugin_api_keys(key_hash);

-- User's keys listing
CREATE INDEX IF NOT EXISTS idx_plugin_api_keys_user_id ON plugin_api_keys(user_id);

-- Active keys filter (exclude revoked)
CREATE INDEX IF NOT EXISTS idx_plugin_api_keys_active ON plugin_api_keys(user_id) 
    WHERE revoked_at IS NULL;

-- Audit log queries
CREATE INDEX IF NOT EXISTS idx_plugin_api_key_logs_api_key_id ON plugin_api_key_logs(api_key_id);
CREATE INDEX IF NOT EXISTS idx_plugin_api_key_logs_created_at ON plugin_api_key_logs(created_at DESC);


-- ============================================================================
-- SECTION 3: Row Level Security (RLS)
-- ============================================================================

-- Enable RLS on all tables
ALTER TABLE plugin_api_keys ENABLE ROW LEVEL SECURITY;
ALTER TABLE plugin_api_key_logs ENABLE ROW LEVEL SECURITY;

-- API Keys: Users can only view their own keys
CREATE POLICY "Users can view own API keys"
    ON plugin_api_keys FOR SELECT
    USING (auth.uid() = user_id);

-- API Keys: Users can create keys for themselves
CREATE POLICY "Users can create own API keys"
    ON plugin_api_keys FOR INSERT
    WITH CHECK (auth.uid() = user_id);

-- API Keys: Users can update their own keys (for revoking)
CREATE POLICY "Users can update own API keys"
    ON plugin_api_keys FOR UPDATE
    USING (auth.uid() = user_id);

-- API Keys: Users can delete their own keys
CREATE POLICY "Users can delete own API keys"
    ON plugin_api_keys FOR DELETE
    USING (auth.uid() = user_id);

-- Logs: Users can view logs for their own keys
CREATE POLICY "Users can view own API key logs"
    ON plugin_api_key_logs FOR SELECT
    USING (
        EXISTS (
            SELECT 1 FROM plugin_api_keys 
            WHERE plugin_api_keys.id = plugin_api_key_logs.api_key_id 
            AND plugin_api_keys.user_id = auth.uid()
        )
    );

-- Logs: Service role can insert logs (via Edge Function)
CREATE POLICY "Service role can insert API key logs"
    ON plugin_api_key_logs FOR INSERT
    WITH CHECK (true);


-- ============================================================================
-- SECTION 4: Database Functions
-- ============================================================================

-- Function 4.1: Validate API key and return user info
-- -----------------------------------------------------------------------------
-- Purpose: Check if an API key is valid and return associated user info
-- Used by: Edge Function for authenticating API requests
-- Returns: user_id, scopes, key_id if valid; NULL if invalid/expired/revoked
-- -----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION validate_plugin_api_key(p_key_hash TEXT)
RETURNS TABLE (
    user_id UUID,
    api_key_id UUID,
    scopes TEXT[],
    key_name TEXT
) AS $$
BEGIN
    RETURN QUERY
    SELECT
        pak.user_id,
        pak.id AS api_key_id,
        pak.scopes,
        pak.name AS key_name
    FROM plugin_api_keys pak
    WHERE pak.key_hash = p_key_hash
    AND pak.revoked_at IS NULL
    AND (pak.expires_at IS NULL OR pak.expires_at > NOW());
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

COMMENT ON FUNCTION validate_plugin_api_key IS 'Validate API key hash and return user info if valid';


-- Function 4.2: Update last_used_at timestamp
-- -----------------------------------------------------------------------------
-- Purpose: Track when API key was last used
-- Used by: Edge Function after successful authentication
-- -----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION update_api_key_last_used(p_key_id UUID)
RETURNS VOID AS $$
BEGIN
    UPDATE plugin_api_keys
    SET last_used_at = NOW()
    WHERE id = p_key_id;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

COMMENT ON FUNCTION update_api_key_last_used IS 'Update last_used_at timestamp for API key';


-- Function 4.3: Log API key action
-- -----------------------------------------------------------------------------
-- Purpose: Create audit log entry for API key usage
-- Used by: Edge Function for audit trail
-- -----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION log_api_key_action(
    p_api_key_id UUID,
    p_action TEXT,
    p_plugin_id TEXT DEFAULT NULL,
    p_ip_address TEXT DEFAULT NULL,
    p_user_agent TEXT DEFAULT NULL,
    p_success BOOLEAN DEFAULT TRUE,
    p_error_message TEXT DEFAULT NULL
)
RETURNS UUID AS $$
DECLARE
    v_log_id UUID;
BEGIN
    INSERT INTO plugin_api_key_logs (
        api_key_id, action, plugin_id, ip_address, user_agent, success, error_message
    )
    VALUES (
        p_api_key_id, p_action, p_plugin_id, p_ip_address, p_user_agent, p_success, p_error_message
    )
    RETURNING id INTO v_log_id;
    
    RETURN v_log_id;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

COMMENT ON FUNCTION log_api_key_action IS 'Create audit log entry for API key action';


-- ============================================================================
-- SECTION 5: Grants
-- ============================================================================

-- Grant execute permissions on functions to service_role (Edge Functions)
GRANT EXECUTE ON FUNCTION validate_plugin_api_key TO service_role;
GRANT EXECUTE ON FUNCTION update_api_key_last_used TO service_role;
GRANT EXECUTE ON FUNCTION log_api_key_action TO service_role;
