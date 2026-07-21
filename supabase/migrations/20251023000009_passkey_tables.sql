-- ============================================================================
-- BOSS Database Schema: WebAuthn/Passkey Tables
-- ============================================================================
-- File: 20251023000009_passkey_tables.sql
-- Description: WebAuthn/FIDO2 passkey tables for biometric authentication.
--              Supports Touch ID (macOS), Windows Hello (Windows), security
--              keys, and cross-device registration/authentication flows.
-- Dependencies:
--   - File 1: extensions_and_types.sql (challenge_type ENUM, uuid-ossp, pgcrypto)
--   - File 8: rbac_tables.sql (users table)
-- Tables: 3 tables + 1 view
-- Standards: WebAuthn Level 2, FIDO2
-- Flow Types: Local biometric, cross-device QR code
-- ============================================================================


-- ============================================================================
-- WebAuthn/Passkey Architecture Overview
-- ============================================================================
--
-- This schema implements WebAuthn/FIDO2 passkey authentication supporting:
--   1. Local biometric authentication (Touch ID, Windows Hello, fingerprint)
--   2. Cross-device registration via QR code (desktop → mobile)
--   3. Cross-device authentication via QR code (desktop → mobile)
--   4. Security key support (YubiKey, Titan Key, etc.)
--
-- Authentication Flows:
--
--   A. Local Biometric Registration (Single Device):
--      1. User clicks "Register Passkey" → Desktop generates challenge
--      2. Desktop stores challenge in passkey_challenges table
--      3. User authenticates via Touch ID/Windows Hello
--      4. Desktop verifies signature and stores credential in user_passkeys
--      5. User can now login with biometric on this device
--
--   B. Cross-Device Registration (Desktop → Mobile):
--      1. Desktop generates session_id and challenge
--      2. Desktop calls create_mobile_registration_session() → stores in passkey_challenges
--      3. Desktop displays QR code with session_id
--      4. Mobile scans QR code → retrieves challenge
--      5. Mobile registers passkey (biometric/security key)
--      6. Mobile stores credential in user_passkeys
--      7. Desktop polls get_session_status() until status = 'completed'
--      8. User can now login on mobile device
--
--   C. Cross-Device Authentication (Desktop → Mobile):
--      1. Desktop generates session_id and challenge
--      2. Desktop stores challenge in passkey_challenges
--      3. Desktop displays QR code with session_id
--      4. Mobile scans QR code → retrieves challenge
--      5. Mobile authenticates with passkey (biometric)
--      6. Mobile stores result in completed_authentications
--      7. Desktop polls completed_authentications until found
--      8. Desktop retrieves session tokens and logs user in
--
-- Table Relationships:
--   users ←→ user_passkeys (ONE user → MANY passkeys)
--   users ←→ passkey_challenges (ONE user → MANY challenges)
--   users ←→ completed_authentications (ONE user → MANY completed auths)
--
-- Security Considerations:
--   - Challenges expire after 5 minutes (standard WebAuthn timeout)
--   - Completed authentications expire after 5 minutes (prevent token theft)
--   - Probabilistic cleanup triggers prevent table bloat
--   - Public keys stored for signature verification (not private keys)
--   - RLS policies restrict access to own passkeys only
--
-- ============================================================================


-- ============================================================================
-- SECTION 1: Passkey Storage (1 table + 1 view)
-- ============================================================================
-- Purpose: Store registered passkey credentials for users


-- Table 1.1: user_passkeys
-- -----------------------------------------------------------------------------
-- Purpose: Store WebAuthn/FIDO2 passkey credentials for users
-- Why Needed: Persist passkey registration data for authentication
-- Standards: WebAuthn Level 2, FIDO2
--
-- Passkey Registration Data:
--   - credential_id: Unique identifier for this passkey (from WebAuthn API)
--   - public_key: Public key for verifying signatures (NOT private key)
--   - display_name: User-friendly name (e.g., "MacBook Touch ID", "iPhone Passkey")
--   - transports: Available transport methods (internal, usb, nfc, ble, hybrid)
--   - attestation_object: Optional attestation data (for security key verification)
--
-- Columns:
--   - id: Passkey UUID (PRIMARY KEY, auto-generated via gen_random_uuid)
--   - user_id: Foreign key to users table (who owns this passkey)
--   - credential_id: Base64url-encoded credential ID (from WebAuthn)
--   - public_key: Base64url-encoded public key (for signature verification)
--   - display_name: User-friendly name for this passkey
--   - transports: Array of transport methods ['internal', 'usb', 'nfc', 'ble', 'hybrid']
--   - created_at: Registration timestamp (epoch milliseconds, NOT standard timestamp)
--   - last_used_at: Last authentication timestamp (epoch milliseconds)
--   - active: Boolean flag (false = disabled passkey, true = usable)
--   - attestation_object: Base64url-encoded attestation object (optional)
--   - created_by_ip: IP address of device that registered passkey
--   - user_agent: Browser/device user agent string
--
-- Transport Types:
--   - internal: Platform authenticator (Touch ID, Windows Hello, Android biometric)
--   - usb: USB security key (YubiKey, Titan Key)
--   - nfc: NFC security key (tap to authenticate)
--   - ble: Bluetooth security key (wireless)
--   - hybrid: Cross-device authentication (QR code flow)
--
-- Timestamp Format:
--   - created_at and last_used_at use epoch milliseconds (bigint)
--   - Why: Client compatibility (JavaScript Date.now() returns milliseconds)
--   - Conversion: EXTRACT(epoch FROM now()) * 1000
--
-- Passkey Lifecycle:
--   1. Register passkey → INSERT INTO user_passkeys (credential_id, public_key, display_name)
--   2. Authenticate → UPDATE user_passkeys SET last_used_at = NOW() WHERE credential_id = '...'
--   3. Disable passkey → UPDATE user_passkeys SET active = false WHERE id = '...'
--   4. Delete passkey → DELETE FROM user_passkeys WHERE id = '...'
--
-- Multiple Passkeys:
--   - Users can register multiple passkeys (phone, laptop, security key)
--   - Each passkey has unique credential_id
--   - User can authenticate with any active passkey
--
-- RLS Policies:
--   - Users can only view/edit their own passkeys (user_id = auth.uid())
--   - Admins cannot access other users' passkeys (privacy protection)
--
-- Security Notes:
--   - Public keys are safe to store (cannot be used to forge signatures)
--   - Private keys never leave the device (stored in secure enclave/TPM)
--   - credential_id is unique per user+device+RP (relying party)
--
-- Usage:
--   -- List user's passkeys
--   SELECT id, display_name, transports, created_at
--   FROM user_passkeys
--   WHERE user_id = auth.uid() AND active = true;
--
--   -- Verify signature during authentication
--   SELECT public_key FROM user_passkeys
--   WHERE credential_id = '<credential-id-from-auth>';
CREATE TABLE IF NOT EXISTS "public"."user_passkeys" (
    "id" "uuid" DEFAULT "gen_random_uuid"() NOT NULL,
    "user_id" "uuid" NOT NULL,
    "credential_id" "text" NOT NULL,
    "public_key" "text" NOT NULL,
    "display_name" "text" NOT NULL,
    "transports" "text"[] DEFAULT ARRAY['internal'::"text"],
    "created_at" bigint DEFAULT (EXTRACT(epoch FROM "now"()) * (1000)::numeric),
    "last_used_at" bigint,
    "active" boolean DEFAULT true,
    "attestation_object" "text",
    "created_by_ip" "inet" DEFAULT "inet_client_addr"(),
    "user_agent" "text"
);

ALTER TABLE "public"."user_passkeys" OWNER TO "postgres";

COMMENT ON TABLE "public"."user_passkeys" IS 'WebAuthn/FIDO2 passkey credentials for users';

COMMENT ON COLUMN "public"."user_passkeys"."credential_id" IS 'Base64url-encoded credential ID from WebAuthn';

COMMENT ON COLUMN "public"."user_passkeys"."public_key" IS 'Base64url-encoded public key for signature verification';

COMMENT ON COLUMN "public"."user_passkeys"."transports" IS 'Available transport methods (internal, usb, nfc, ble, hybrid)';

COMMENT ON COLUMN "public"."user_passkeys"."attestation_object" IS 'Base64url-encoded attestation object (optional)';



-- View 1.1: active_user_passkeys
-- -----------------------------------------------------------------------------
-- Purpose: Filtered view of user_passkeys showing only active passkeys
-- Why Needed: Client-side passkey management UI (hide disabled passkeys)
-- Security: security_invoker='on' (runs with caller's permissions, respects RLS)
--
-- Filtered Columns:
--   - Excludes sensitive fields (public_key, attestation_object)
--   - Excludes metadata fields (created_by_ip, user_agent)
--   - Only shows active passkeys (active = true)
--
-- View Columns:
--   - id: Passkey UUID (for deletion)
--   - user_id: Owner UUID
--   - credential_id: Credential ID (for authentication)
--   - display_name: User-friendly name
--   - transports: Available transport methods
--   - created_at: Registration timestamp (epoch milliseconds)
--   - last_used_at: Last authentication timestamp (epoch milliseconds)
--
-- Security Invoker:
--   - security_invoker='on' means view runs with caller's RLS context
--   - Users can only see their own passkeys (via RLS on user_passkeys table)
--   - No privilege escalation (view doesn't bypass RLS)
--
-- Usage:
--   -- List active passkeys for current user
--   SELECT * FROM active_user_passkeys
--   WHERE user_id = auth.uid()
--   ORDER BY created_at DESC;
--
--   -- Count active passkeys
--   SELECT COUNT(*) FROM active_user_passkeys
--   WHERE user_id = auth.uid();
CREATE OR REPLACE VIEW "public"."active_user_passkeys" WITH ("security_invoker"='on') AS
 SELECT "id",
    "user_id",
    "credential_id",
    "display_name",
    "transports",
    "created_at",
    "last_used_at"
   FROM "public"."user_passkeys"
  WHERE ("active" = true);

ALTER VIEW "public"."active_user_passkeys" OWNER TO "postgres";



-- ============================================================================
-- SECTION 2: Challenge Storage (1 table)
-- ============================================================================
-- Purpose: Temporary storage for WebAuthn challenges during auth flows


-- Table 2.1: passkey_challenges
-- -----------------------------------------------------------------------------
-- Purpose: Temporary storage for WebAuthn challenges during registration/authentication
-- Why Needed: Store challenges for verification during WebAuthn flows
-- Expiration: Challenges expire after 5 minutes (standard WebAuthn timeout)
--
-- Challenge Types (challenge_type ENUM):
--   - registration: Challenge for registering a new passkey
--   - authentication: Challenge for authenticating with existing passkey
--
-- Cross-Device Flow Fields:
--   - session_id: Unique session ID for QR code (desktop generates, mobile scans)
--   - status: Session status ('pending', 'in_progress', 'completed', 'failed', 'expired')
--   - user_email: Email for lookup (mobile uses email to find user)
--
-- Session Status Values:
--   - pending: Challenge created, waiting for mobile to scan QR code
--   - in_progress: Mobile scanned QR, working on registration/authentication
--   - completed: Registration/authentication successful
--   - failed: Registration/authentication failed (wrong user, signature error, etc.)
--   - expired: Challenge expired (>5 minutes old)
--
-- Columns:
--   - id: Challenge UUID (PRIMARY KEY, auto-generated via gen_random_uuid)
--   - user_id: Foreign key to users table (who is registering/authenticating)
--   - challenge: Base64url-encoded random challenge (32+ bytes)
--   - type: Challenge type ENUM ('registration' or 'authentication')
--   - expires_at: Expiration timestamp (typically NOW() + 5 minutes)
--   - created_at: Challenge creation timestamp
--   - created_by_ip: IP address of device that created challenge
--   - session_id: Unique session ID for cross-device flows (NULL for local flows)
--   - status: Session status (for cross-device flows)
--   - user_email: User email (for cross-device lookup)
--
-- Challenge Lifecycle:
--   1. Create challenge → INSERT INTO passkey_challenges (challenge, type, expires_at)
--   2. Mobile scans QR → UPDATE status = 'in_progress'
--   3. Complete registration → UPDATE status = 'completed'
--   4. Verify challenge → SELECT challenge WHERE expires_at > NOW()
--   5. Auto-cleanup → Trigger deletes expired challenges (probabilistic)
--
-- Expiration Logic:
--   - Challenges expire after 5 minutes (WebAuthn standard)
--   - expires_at checked during verification (reject if expired)
--   - Cleanup trigger deletes expired challenges (10% probability on insert)
--   - Manual cleanup: SELECT clean_expired_passkey_challenges()
--
-- Cross-Device Flow:
--   1. Desktop: Generate session_id and challenge
--   2. Desktop: INSERT INTO passkey_challenges (session_id, challenge, status='pending')
--   3. Desktop: Display QR code with session_id
--   4. Mobile: Scan QR, extract session_id
--   5. Mobile: SELECT * FROM passkey_challenges WHERE session_id = '...'
--   6. Mobile: Perform WebAuthn registration/authentication
--   7. Mobile: UPDATE status = 'completed'
--   8. Desktop: Poll get_session_status() until status = 'completed'
--
-- Security Notes:
--   - Challenges should be cryptographically random (32+ bytes)
--   - Challenges are single-use (verified once, then deleted/marked expired)
--   - Short expiration (5 minutes) limits replay attack window
--
-- Usage:
--   -- Create registration challenge (via function)
--   SELECT create_mobile_registration_session(
--     'user@example.com',
--     'base64url-encoded-challenge',
--     'unique-session-uuid'
--   );
--
--   -- Poll session status (cross-device flow)
--   SELECT * FROM get_session_status('session-uuid');
--   -- Returns: {session_id, status, user_email, created_at, expires_at}
--
--   -- Manual cleanup (if needed)
--   SELECT clean_expired_passkey_challenges();
CREATE TABLE IF NOT EXISTS "public"."passkey_challenges" (
    "id" "uuid" DEFAULT "gen_random_uuid"() NOT NULL,
    "user_id" "uuid",
    "challenge" "text" NOT NULL,
    "type" "public"."challenge_type" NOT NULL,
    "expires_at" timestamp with time zone NOT NULL,
    "created_at" timestamp with time zone DEFAULT "now"(),
    "created_by_ip" "inet" DEFAULT "inet_client_addr"(),
    "session_id" "text",
    "status" "text" DEFAULT 'pending'::"text",
    "user_email" "text",
    CONSTRAINT "passkey_challenges_status_check" CHECK (("status" = ANY (ARRAY['pending'::"text", 'in_progress'::"text", 'completed'::"text", 'failed'::"text", 'expired'::"text"])))
);

ALTER TABLE "public"."passkey_challenges" OWNER TO "postgres";

COMMENT ON TABLE "public"."passkey_challenges" IS 'Temporary storage for WebAuthn challenges during registration/authentication';

COMMENT ON COLUMN "public"."passkey_challenges"."type" IS 'Challenge type: registration or authentication';

COMMENT ON COLUMN "public"."passkey_challenges"."expires_at" IS 'Challenge expiration timestamp (typically 5 minutes)';

COMMENT ON COLUMN "public"."passkey_challenges"."session_id" IS 'Unique session ID for tracking cross-device WebAuthn flows';

COMMENT ON COLUMN "public"."passkey_challenges"."status" IS 'Session status: pending, in_progress, completed, failed, expired';

COMMENT ON COLUMN "public"."passkey_challenges"."user_email" IS 'User email for cross-device flows (lookup purposes)';



-- ============================================================================
-- SECTION 3: Completed Authentication Storage (1 table)
-- ============================================================================
-- Purpose: Temporary storage for completed cross-device authentications


-- Table 3.1: completed_authentications
-- -----------------------------------------------------------------------------
-- Purpose: Temporary storage for completed cross-device authentication results
-- Why Needed: Mobile completes auth → stores result → desktop retrieves tokens
-- Expiration: Results expire after 5 minutes (short-lived for security)
--
-- Cross-Device Authentication Flow:
--   1. Desktop generates challenge and session_id
--   2. Desktop displays QR code with session_id
--   3. Mobile scans QR, retrieves challenge
--   4. Mobile authenticates with passkey (biometric)
--   5. Mobile calls Supabase Auth API to generate session tokens
--   6. Mobile stores result in completed_authentications table
--   7. Desktop polls completed_authentications WHERE session_id = '...'
--   8. Desktop retrieves session_token, access_token, refresh_token
--   9. Desktop establishes authenticated session
--  10. Auto-cleanup deletes expired result (probabilistic trigger)
--
-- Token Storage:
--   - session_token: NOT USED (legacy field, Supabase doesn't return session token)
--   - access_token: JWT access token (short-lived, ~1 hour)
--   - refresh_token: Refresh token (long-lived, used to get new access tokens)
--   - expires_at: Access token expiration (epoch milliseconds)
--
-- Columns:
--   - id: Result UUID (PRIMARY KEY, auto-generated via gen_random_uuid)
--   - challenge: WebAuthn challenge that was completed (for verification)
--   - user_id: Foreign key to users table (who authenticated)
--   - email: User email (for display on desktop)
--   - session_token: Legacy field (NOT USED in current implementation)
--   - access_token: JWT access token
--   - refresh_token: Refresh token
--   - expires_at: Token expiration (epoch milliseconds)
--   - created_at: When authentication was completed
--   - expires_at_timestamp: When this record should be deleted (NOW() + 5 minutes)
--   - session_id: Session ID for desktop polling
--
-- Expiration Logic:
--   - expires_at_timestamp: 5 minutes from creation (record cleanup)
--   - expires_at: Access token expiration (~1 hour, from Supabase)
--   - Cleanup trigger deletes expired records (10% probability on insert)
--   - Manual cleanup: SELECT cleanup_expired_completed_authentications()
--
-- Desktop Polling Pattern:
--   while (status !== 'completed' && !timeout) {
--     const result = await supabase
--       .from('completed_authentications')
--       .select('*')
--       .eq('session_id', sessionId)
--       .single();
--
--     if (result.data) {
--       // Authentication completed! Retrieve tokens
--       const { access_token, refresh_token, expires_at } = result.data;
--       await supabase.auth.setSession({ access_token, refresh_token });
--       break;
--     }
--
--     await sleep(1000);  // Poll every 1 second
--   }
--
-- Security Considerations:
--   - Short expiration (5 minutes) limits token theft window
--   - Tokens should be deleted after desktop retrieves them (one-time use)
--   - challenge field ensures result matches expected authentication
--   - RLS policies restrict access to own authentications only
--
-- Token Format:
--   - access_token: JWT (can be decoded to see claims)
--   - refresh_token: Opaque string (cannot be decoded)
--   - Both are sensitive and should be transmitted over HTTPS only
--
-- Usage:
--   -- Mobile stores completed authentication
--   INSERT INTO completed_authentications (
--     challenge, user_id, email, access_token, refresh_token,
--     expires_at, session_id
--   ) VALUES (
--     'challenge-from-qr', '<user-uuid>', 'user@example.com',
--     'jwt-access-token', 'refresh-token',
--     1234567890000, 'session-uuid'
--   );
--
--   -- Desktop polls for completion
--   SELECT * FROM completed_authentications
--   WHERE session_id = 'session-uuid'
--   AND expires_at_timestamp > NOW()
--   LIMIT 1;
--
--   -- Manual cleanup (if needed)
--   SELECT cleanup_expired_completed_authentications();
CREATE TABLE IF NOT EXISTS "public"."completed_authentications" (
    "id" "uuid" DEFAULT "gen_random_uuid"() NOT NULL,
    "challenge" "text" NOT NULL,
    "user_id" "uuid" NOT NULL,
    "email" "text",
    "session_token" "text",
    "access_token" "text",
    "refresh_token" "text",
    "expires_at" bigint,
    "created_at" timestamp with time zone DEFAULT "now"(),
    "expires_at_timestamp" timestamp with time zone DEFAULT ("now"() + '00:05:00'::interval),
    "session_id" "text"
);

ALTER TABLE "public"."completed_authentications" OWNER TO "postgres";

COMMENT ON TABLE "public"."completed_authentications" IS 'Temporary storage for completed cross-device authentications';

COMMENT ON COLUMN "public"."completed_authentications"."challenge" IS 'WebAuthn challenge that was completed';

COMMENT ON COLUMN "public"."completed_authentications"."user_id" IS 'User who completed the authentication';

COMMENT ON COLUMN "public"."completed_authentications"."expires_at_timestamp" IS 'When this record should be cleaned up';

COMMENT ON COLUMN "public"."completed_authentications"."session_id" IS 'Session ID for polling authentication status in cross-device flows';


-- ============================================================================
-- End of File: passkey_tables.sql
-- ============================================================================
-- Next Migration: 20251023000010_secret_tables.sql (Secret Tables)
-- ============================================================================
