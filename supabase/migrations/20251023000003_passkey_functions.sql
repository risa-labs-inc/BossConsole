-- ============================================================================
-- BOSS Database Schema: Passkey Authentication Functions
-- ============================================================================
-- File: 20251023000003_passkey_functions.sql
-- Description: WebAuthn/FIDO2 passkey functions for biometric authentication
--              and cross-device registration flows. Supports Touch ID (macOS),
--              Windows Hello (Windows), and security keys.
-- Dependencies:
--   - File 1: extensions_and_types.sql (challenge_type ENUM)
--   - Note: Tables will be created in File 9 (passkey_tables.sql)
-- Functions: 4 total
-- WebAuthn Standard: FIDO2/WebAuthn Level 2
-- ============================================================================


-- ============================================================================
-- SECTION 1: Challenge Lifecycle Management (2 functions)
-- ============================================================================
-- Purpose: Create and cleanup WebAuthn challenges for registration/authentication


-- Function 1.1: create_mobile_registration_session
-- -----------------------------------------------------------------------------
-- Purpose: Create a passkey registration challenge for cross-device flow
-- Use Case: Desktop generates QR code → Mobile scans → Creates challenge →
--           Mobile registers passkey → Desktop polls for completion
-- Flow:
--   1. Desktop: User initiates "Add Passkey via Mobile"
--   2. Desktop: Generates session_id and challenge, calls this function
--   3. Desktop: Displays QR code with session_id
--   4. Mobile: Scans QR, extracts session_id
--   5. Mobile: Retrieves challenge from passkey_challenges table
--   6. Mobile: Performs WebAuthn registration
--   7. Desktop: Polls get_session_status() until status = 'completed'
--
-- Parameters:
--   - p_user_email: Email of user registering passkey (for lookup)
--   - p_challenge: Base64-encoded random challenge (32 bytes minimum)
--   - p_session_id: Unique session ID for QR code (UUID recommended)
-- Returns: UUID of created challenge record
-- Security:
--   - SECURITY DEFINER to insert into passkey_challenges
--   - Validates email is confirmed before allowing registration
-- Expiration: Challenge expires after 5 minutes
-- Status: Initial status is 'pending' (waiting for mobile to start)
-- Usage:
--   SELECT create_mobile_registration_session(
--     'user@example.com',
--     'base64-random-challenge-here',
--     'unique-session-uuid'
--   );
CREATE OR REPLACE FUNCTION "public"."create_mobile_registration_session"("p_user_email" "text", "p_challenge" "text", "p_session_id" "text") RETURNS "uuid"
    LANGUAGE "plpgsql" SECURITY DEFINER
    AS $$
DECLARE
  challenge_id UUID;
  user_uuid UUID;
BEGIN
  -- Look up user by email
  -- IMPORTANT: Only confirmed emails can register passkeys
  SELECT id INTO user_uuid
  FROM auth.users
  WHERE email = p_user_email
  AND email_confirmed_at IS NOT NULL;

  IF user_uuid IS NULL THEN
    RAISE EXCEPTION 'User not found or email not confirmed: %', p_user_email;
  END IF;

  -- Insert challenge record for cross-device registration
  -- Type: 'registration' (not 'authentication')
  -- Status: 'pending' → 'in_progress' → 'completed'
  -- Expires: 5 minutes (standard WebAuthn timeout)
  INSERT INTO passkey_challenges (
    user_id,
    challenge,
    type,
    expires_at,
    session_id,
    status,
    user_email
  ) VALUES (
    user_uuid,
    p_challenge,
    'registration',
    NOW() + INTERVAL '5 minutes',
    p_session_id,
    'pending',
    p_user_email
  ) RETURNING id INTO challenge_id;

  RETURN challenge_id;
END;
$$;

ALTER FUNCTION "public"."create_mobile_registration_session"("p_user_email" "text", "p_challenge" "text", "p_session_id" "text") OWNER TO "postgres";

COMMENT ON FUNCTION "public"."create_mobile_registration_session"("p_user_email" "text", "p_challenge" "text", "p_session_id" "text") IS 'Helper function to create mobile WebAuthn registration sessions with email lookup';



-- Function 1.2: clean_expired_passkey_challenges
-- -----------------------------------------------------------------------------
-- Purpose: Cleanup expired and old passkey challenges to prevent table bloat
-- Trigger: Called by trigger AFTER INSERT on passkey_challenges (10% probability)
-- Why Probabilistic: Reduces overhead - not every insert needs cleanup
-- Cleanup Rules:
--   1. Delete challenges where expires_at < NOW() (hard expiration)
--   2. Delete old failed/expired sessions (older than 1 hour, safe to purge)
--   3. Mark stale in_progress sessions as expired (older than 15 minutes)
--
-- Performance: Uses indexes on expires_at, status, and created_at for speed
-- Security: SECURITY DEFINER to access passkey_challenges table
-- Usage: Automatically triggered, but can be called manually:
--   SELECT clean_expired_passkey_challenges();
CREATE OR REPLACE FUNCTION "public"."clean_expired_passkey_challenges"() RETURNS "void"
    LANGUAGE "plpgsql" SECURITY DEFINER
    AS $$
BEGIN
  -- Rule 1: Delete expired challenges (by timestamp)
  -- These are past their 5-minute expiration window
  DELETE FROM passkey_challenges
  WHERE expires_at < NOW();

  -- Rule 2: Delete old failed/expired sessions (older than 1 hour)
  -- Keep recent failures for debugging, but purge old ones
  DELETE FROM passkey_challenges
  WHERE status IN ('failed', 'expired')
  AND created_at < NOW() - INTERVAL '1 hour';

  -- Rule 3: Mark very old in_progress sessions as expired
  -- If mobile hasn't completed registration within 15 minutes, assume failure
  -- Desktop should stop polling after this timeout
  UPDATE passkey_challenges
  SET status = 'expired'
  WHERE status = 'in_progress'
  AND created_at < NOW() - INTERVAL '15 minutes';
END;
$$;

ALTER FUNCTION "public"."clean_expired_passkey_challenges"() OWNER TO "postgres";



-- ============================================================================
-- SECTION 2: Session Status Polling (1 function)
-- ============================================================================
-- Purpose: Allow desktop to poll for mobile registration completion


-- Function 2.1: get_session_status
-- -----------------------------------------------------------------------------
-- Purpose: Check the status of a mobile registration session
-- Use Case: Desktop polling loop to detect when mobile completes registration
-- Polling Pattern:
--   Desktop: while (status !== 'completed' && !timeout) {
--     const result = await getSessionStatus(sessionId);
--     if (result.status === 'completed') { /* Success! */ }
--     await sleep(1000); // Poll every 1 second
--   }
--
-- Parameters:
--   - p_session_id: Session ID from QR code
-- Returns: TABLE with columns:
--   - session_id: Echo back the session ID
--   - status: 'pending' | 'in_progress' | 'completed' | 'failed' | 'expired'
--   - user_email: Email of user (for display on desktop)
--   - created_at: When session was created
--   - expires_at: When session expires (5 minutes from creation)
-- Security: SECURITY DEFINER to read passkey_challenges
-- Filtering:
--   - Only returns 'registration' type challenges (not authentication)
--   - Returns most recent matching session (ORDER BY created_at DESC)
--   - LIMIT 1 ensures single result
-- Usage:
--   SELECT * FROM get_session_status('session-uuid-here');
CREATE OR REPLACE FUNCTION "public"."get_session_status"("p_session_id" "text") RETURNS TABLE("session_id" "text", "status" "text", "user_email" "text", "created_at" timestamp with time zone, "expires_at" timestamp with time zone)
    LANGUAGE "plpgsql" SECURITY DEFINER
    AS $$
BEGIN
  RETURN QUERY
  SELECT
    pc.session_id,
    pc.status,
    pc.user_email,
    pc.created_at,
    pc.expires_at
  FROM passkey_challenges pc
  WHERE pc.session_id = p_session_id
  AND pc.type = 'registration'  -- Only registration sessions (not auth)
  ORDER BY pc.created_at DESC   -- Most recent first (in case of duplicates)
  LIMIT 1;                       -- Single result
END;
$$;

ALTER FUNCTION "public"."get_session_status"("p_session_id" "text") OWNER TO "postgres";

COMMENT ON FUNCTION "public"."get_session_status"("p_session_id" "text") IS 'Helper function to check the status of a mobile registration session';



-- ============================================================================
-- SECTION 3: Authentication Session Cleanup (1 function)
-- ============================================================================
-- Purpose: Cleanup completed authentication sessions after desktop consumes them


-- Function 3.1: cleanup_expired_completed_authentications
-- -----------------------------------------------------------------------------
-- Purpose: Remove expired authentication completion records
-- Use Case: After cross-device authentication completes, desktop retrieves
--           the result from completed_authentications table, then this
--           function removes old records to prevent table bloat
-- Trigger: Called by trigger AFTER INSERT on completed_authentications (10% probability)
-- Why Separate Table: completed_authentications stores final auth results
--                      (tokens, user info) separately from challenges
-- Expiration Logic: Records have expires_at_timestamp field
--   - Desktop should consume result within this window
--   - After expiration, result is no longer valid and can be purged
--
-- Security: SECURITY DEFINER to delete from completed_authentications
-- Performance: Uses index on expires_at_timestamp for fast deletion
-- Usage: Automatically triggered, but can be called manually:
--   SELECT cleanup_expired_completed_authentications();
CREATE OR REPLACE FUNCTION "public"."cleanup_expired_completed_authentications"() RETURNS "void"
    LANGUAGE "plpgsql"
    AS $$
BEGIN
  -- Delete authentication results past their expiration
  -- Typically expires_at_timestamp = completed_at + 2 minutes
  DELETE FROM completed_authentications
  WHERE expires_at_timestamp < NOW();
END;
$$;

ALTER FUNCTION "public"."cleanup_expired_completed_authentications"() OWNER TO "postgres";


-- ============================================================================
-- End of File: passkey_functions.sql
-- ============================================================================
-- Next Migration: 20251023000004_secret_functions.sql (Secret Functions)
-- ============================================================================
