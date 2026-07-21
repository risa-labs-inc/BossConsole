-- ============================================================================
-- BOSS Database Schema: Extensions and Custom Types
-- ============================================================================
-- File: 20251023000001_extensions_and_types.sql
-- Description: Foundation layer that enables required PostgreSQL extensions
--              and defines custom ENUM types used throughout the schema.
--              This file MUST run first before all other migrations.
-- Dependencies: None
-- Contents:
--   - PostgreSQL configuration (SET statements)
--   - 5 PostgreSQL extensions (pgcrypto, uuid-ossp, pg_graphql, etc.)
--   - 1 custom ENUM type (challenge_type)
-- ============================================================================


-- ============================================================================
-- SECTION 1: PostgreSQL Configuration
-- ============================================================================
-- Purpose: Set session-level configuration for safe schema operations
-- Note: These settings only apply during migration execution

-- Timeouts: Disable to allow long-running operations during migration
SET statement_timeout = 0;                    -- No timeout for SQL statements
SET lock_timeout = 0;                         -- No timeout for lock acquisition
SET idle_in_transaction_session_timeout = 0; -- No timeout for idle transactions

-- Character encoding: Ensure UTF-8 support for international characters
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;         -- Treat backslashes literally in strings

-- Search path: Clear to force fully-qualified names (security best practice)
SELECT pg_catalog.set_config('search_path', '', false);

-- Migration settings
SET check_function_bodies = false;            -- Skip function body validation (faster)
SET xmloption = content;                      -- XML handling mode
SET client_min_messages = warning;            -- Reduce log noise (only warnings+)
SET row_security = off;                       -- Disable RLS during migration (we'll enable per-table)


COMMENT ON SCHEMA "public" IS 'standard public schema';



-- ============================================================================
-- SECTION 2: PostgreSQL Extensions
-- ============================================================================
-- Purpose: Enable required PostgreSQL extensions for encryption, GraphQL,
--          performance monitoring, and UUID generation


-- Extension 1: pg_graphql
-- -----------------------------------------------------------------------------
-- Purpose: Provides GraphQL API introspection and query capabilities
-- Schema: graphql (separate from public for isolation)
-- Used by: Supabase Auto-generated GraphQL API
CREATE EXTENSION IF NOT EXISTS "pg_graphql" WITH SCHEMA "graphql";


-- Extension 2: pg_stat_statements
-- -----------------------------------------------------------------------------
-- Purpose: Track execution statistics for all SQL statements
-- Schema: extensions
-- Used by: Query performance monitoring, slow query detection
-- Dashboard: Supabase Studio > Database > Query Performance
CREATE EXTENSION IF NOT EXISTS "pg_stat_statements" WITH SCHEMA "extensions";


-- Extension 3: pgcrypto
-- -----------------------------------------------------------------------------
-- Purpose: Cryptographic functions for encryption and hashing
-- Schema: extensions
-- Functions Used:
--   - encrypt() / decrypt() - AES encryption for secrets
--   - gen_random_uuid() - UUID generation for primary keys
--   - digest() - SHA-256 hashing (future use)
-- Security: All sensitive data (passwords, recovery codes) encrypted with this
CREATE EXTENSION IF NOT EXISTS "pgcrypto" WITH SCHEMA "extensions";


-- Extension 4: supabase_vault
-- -----------------------------------------------------------------------------
-- Purpose: Secure storage for secrets (encryption keys, API keys)
-- Schema: vault
-- Storage: Master encryption key stored at vault.decrypted_secrets
--          (name = 'master_encryption_key')
-- Security: Only SECURITY DEFINER functions can access vault
-- Setup: Run after migration:
--        SELECT vault.create_secret('<your-256-bit-key>',
--                                   'master_encryption_key',
--                                   'Master key for encrypting user secrets');
CREATE EXTENSION IF NOT EXISTS "supabase_vault" WITH SCHEMA "vault";


-- Extension 5: uuid-ossp
-- -----------------------------------------------------------------------------
-- Purpose: UUID generation functions (legacy, now prefer pgcrypto)
-- Schema: extensions
-- Functions: uuid_generate_v4() - Version 4 (random) UUIDs
-- Note: We primarily use gen_random_uuid() from pgcrypto, but this extension
--       provides additional UUID versions (v1, v3, v5) for special use cases
CREATE EXTENSION IF NOT EXISTS "uuid-ossp" WITH SCHEMA "extensions";



-- ============================================================================
-- SECTION 3: Custom Types (ENUMs)
-- ============================================================================
-- Purpose: Define application-specific ENUM types for type safety


-- Type: challenge_type
-- -----------------------------------------------------------------------------
-- Purpose: Distinguish between WebAuthn challenge types
-- Values:
--   - 'registration' - Challenge for registering a new passkey
--                      (e.g., user adding Touch ID to their account)
--   - 'authentication' - Challenge for logging in with existing passkey
--                        (e.g., user authenticating with Touch ID)
-- Used by: passkey_challenges table (column: type)
-- Security: Ensures only valid challenge types in database
CREATE TYPE "public"."challenge_type" AS ENUM (
    'registration',
    'authentication'
);

ALTER TYPE "public"."challenge_type" OWNER TO "postgres";

COMMENT ON TYPE "public"."challenge_type" IS 'WebAuthn challenge types: registration or authentication';


-- ============================================================================
-- End of File: extensions_and_types.sql
-- ============================================================================
-- Next Migration: 20251023000002_rbac_functions.sql (RBAC Functions)
-- ============================================================================
