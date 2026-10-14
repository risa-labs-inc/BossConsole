-- Close the search_path on the surviving passkey SECURITY DEFINER functions
-- and drop the dead client grant on the challenge cleanup RPC.
--
-- Problem
-- -------
-- Every function added since 20260802000000 follows the repo convention
-- documented there: `SECURITY DEFINER` plus `SET search_path TO ''` and
-- fully-qualified table references - a hardening that 20260802000000 itself
-- called a security fix. The three passkey functions below predate that
-- convention and were never upgraded, so each still resolves its table
-- references through the caller-influenced search_path:
--
--   clean_expired_passkey_challenges()   passkey_challenges
--   create_mobile_registration_session() auth.users, passkey_challenges
--   get_session_status()                 passkey_challenges
--
-- None of these is a live exploit today: exploiting a mutable search_path
-- requires the caller to CREATE objects in a searched schema, and the
-- PostgREST roles (anon/authenticated) cannot. The value is the same as the
-- convention itself: a future grant of CREATE on public, or a restored
-- PUBLIC default, would convert these into privilege-escalation primitives,
-- and the bodies would not error before that day. Closing the search_path
-- now removes the class, matching what every newer SECURITY DEFINER
-- function in this repo already does.
--
-- The same migration closes a second axis on
-- clean_expired_passkey_challenges: its original client grants included anon
-- and authenticated (20251023000014_grants.sql:232-234); the 20260908030000
-- sweep already stripped PUBLIC/anon, leaving authenticated. Nothing invokes
-- it - the probabilistic trigger
-- trigger_cleanup_expired_challenges (20251023000007) inlines its own
-- DELETE rather than calling the RPC, and no edge function or client code
-- references it - so the client grants are dead weight on a SECURITY
-- DEFINER function. Revoke them; keep service_role for operational use.
--
-- Why this is safe
-- ----------------
-- The three RPCs below carry their original bodies verbatim with two mechanical
-- changes only: the SET search_path TO '' clause, and schema
-- qualification of the table references (auth.users, public.passkey_challenges,
-- pg_catalog.now). Signatures, return types, logic, owners and comments are
-- byte-identical in intent. The trigger function additionally becomes SECURITY
-- DEFINER: passkey_challenges has no client DELETE policy, so an INVOKER trigger
-- silently cleans nothing on client inserts. Its fixed body has no parameters
-- or dynamic SQL and deletes only stored rows whose expires_at is already past,
-- so the caller cannot influence which rows qualify. The out-of-repo mobile
-- client reaches the RPCs through the service role, which keeps EXECUTE.
--
-- Deliberately out of scope: the secret-share and recovery-code SECURITY
-- DEFINER functions have larger bodies with authorization logic that
-- deserve their own reviewed migration; this PR covers the passkey
-- lifecycle set so the pattern is established and testable in isolation.

-- 1. clean_expired_passkey_challenges: search_path + revoke dead client grants
CREATE OR REPLACE FUNCTION "public"."clean_expired_passkey_challenges"() RETURNS "void"
    LANGUAGE "plpgsql" SECURITY DEFINER SET search_path TO ''
    AS $$
BEGIN
  -- Rule 1: Delete expired challenges (by timestamp)
  -- These are past their 5-minute expiration window
  DELETE FROM public.passkey_challenges
  WHERE expires_at < pg_catalog.now();

  -- Rule 2: Delete old failed/expired sessions (older than 1 hour)
  -- Keep recent failures for debugging, but purge old ones
  DELETE FROM public.passkey_challenges
  WHERE status IN ('failed', 'expired')
  AND created_at < pg_catalog.now() - INTERVAL '1 hour';

  -- Rule 3: Mark very old in_progress sessions as expired
  -- If mobile hasn't completed registration within 15 minutes, assume failure
  -- Desktop should stop polling after this timeout
  UPDATE public.passkey_challenges
  SET status = 'expired'
  WHERE status = 'in_progress'
  AND created_at < pg_catalog.now() - INTERVAL '15 minutes';
END;
$$;

ALTER FUNCTION "public"."clean_expired_passkey_challenges"() OWNER TO "postgres";

REVOKE ALL ON FUNCTION "public"."clean_expired_passkey_challenges"()
    FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION "public"."clean_expired_passkey_challenges"()
    TO service_role;

-- 2. create_mobile_registration_session: original body + search_path
CREATE OR REPLACE FUNCTION "public"."create_mobile_registration_session"("p_user_email" "text", "p_challenge" "text", "p_session_id" "text") RETURNS "uuid"
    LANGUAGE "plpgsql" SECURITY DEFINER SET search_path TO ''
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
  INSERT INTO public.passkey_challenges (
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
    pg_catalog.now() + INTERVAL '5 minutes',
    p_session_id,
    'pending',
    p_user_email
  ) RETURNING id INTO challenge_id;

  RETURN challenge_id;
END;
$$;

ALTER FUNCTION "public"."create_mobile_registration_session"("p_user_email" "text", "p_challenge" "text", "p_session_id" "text") OWNER TO "postgres";

-- 3. get_session_status: original body + search_path
CREATE OR REPLACE FUNCTION "public"."get_session_status"("p_session_id" "text") RETURNS TABLE("session_id" "text", "status" "text", "user_email" "text", "created_at" timestamp with time zone, "expires_at" timestamp with time zone)
    LANGUAGE "plpgsql" SECURITY DEFINER SET search_path TO ''
    AS $$
BEGIN
  RETURN QUERY
  SELECT
    pc.session_id,
    pc.status,
    pc.user_email,
    pc.created_at,
    pc.expires_at
  FROM public.passkey_challenges pc
  WHERE pc.session_id = p_session_id
  AND pc.type = 'registration'  -- Only registration sessions (not auth)
  ORDER BY pc.created_at DESC   -- Most recent first (in case of duplicates)
  LIMIT 1;                       -- Single result
END;
$$;

ALTER FUNCTION "public"."get_session_status"("p_session_id" "text") OWNER TO "postgres";

-- 4. The nested-trigger regression (review of this PR): the hardened
-- create_mobile_registration_session above inserts under search_path = '',
-- and passkey_challenges' AFTER INSERT trigger then runs
-- trigger_cleanup_expired_challenges() - which had no local search_path and
-- referenced passkey_challenges unqualified. Trigger functions inherit the
-- invoking function's search_path, so the 10% cleanup branch inherited the
-- empty path and aborted registration with relation-not-found.
--
-- Keep the RPC above as the original three-rule SECURITY DEFINER cleanup.
-- The trigger is self-contained instead of delegating to that RPC: client
-- roles intentionally cannot EXECUTE the RPC, while an INSERT trigger must
-- remain able to perform its owner-controlled cleanup.

CREATE OR REPLACE FUNCTION "public"."trigger_cleanup_expired_challenges"() RETURNS "trigger"
    LANGUAGE "plpgsql" SECURITY DEFINER SET search_path TO ''
    AS $$
BEGIN
  IF pg_catalog.random() < 0.1 THEN
    DELETE FROM public.passkey_challenges
    WHERE expires_at < pg_catalog.now();
  END IF;
  RETURN NEW;
END;
$$;

ALTER FUNCTION "public"."trigger_cleanup_expired_challenges"() OWNER TO "postgres";

REVOKE ALL ON FUNCTION "public"."trigger_cleanup_expired_challenges"()
    FROM PUBLIC, anon, authenticated;
