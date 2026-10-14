-- Close the search_path on the last SECURITY DEFINER functions still
-- resolving through the caller-influenced path (BossConsole#772, part 2).
--
-- The sibling migration 20260916130000 (BossConsole#773) covers the three
-- passkey lifecycle functions; a live catalog audit shows the rest of the
-- pre-convention set was already progressively hardened by later migrations
-- (share_secret, unshare_secret, get_secret_shares, handle_new_user all
-- carry SET search_path TO '' today).
-- Exactly three remain, verified against the LIVE definitions in pg_proc
-- (pg_get_functiondef), not the original 20251023 files - several of those
-- functions were replaced since by org-support migrations, and hardening
-- from the stale source would have collided with them (the return-type
-- mismatch is what surfaced this):
--
--   find_user_by_email(text)          reads auth.users
--   handle_user_email_update()        writes public.users
--   safe_decrypt_recovery_codes(text) calls public.decrypt_text
--
-- Each body below is the live definition byte-for-byte with two mechanical
-- changes only: the SET search_path TO '' clause, and pg_catalog
-- qualification of the unqualified NOW() in handle_user_email_update. All
-- table references were already schema-qualified.
--
-- safe_decrypt_recovery_codes depends on public.decrypt_text keeping its own
-- SET "search_path" TO 'public, pg_catalog, extensions' (20260914000000): a
-- callee's SET clause overrides the caller's, so the empty path stops at the
-- call boundary. If that pin is ever removed, the WHEN OTHERS handler would
-- convert the resulting 42883 into a silent empty array for every user.
--
-- Not a live exploit today (the honest scoping in the sibling migration
-- 20260916130000, BossConsole#773, applies:
-- exploiting a mutable search_path needs CREATE on a searched schema, which
-- the PostGREST roles lack). This closes the drift the repo's own
-- convention and the Supabase linter demand, before a future CREATE grant
-- or restored PUBLIC default could convert any of these into
-- privilege-escalation primitives. find_user_by_email is additionally the
-- identity-lookup oracle 20260910000000 already revoked from clients - the
-- clause removes the residual definer-path exposure.
--
-- Signatures, return types, bodies, owners and grants are unchanged.

-- 1. find_user_by_email: identity lookup (client-revoked, service-role-only path)
CREATE OR REPLACE FUNCTION public.find_user_by_email(p_email text)
 RETURNS TABLE(id uuid, email text)
 LANGUAGE plpgsql
 SECURITY DEFINER SET search_path TO ''
AS $$
BEGIN
  -- Query Supabase Auth users table
  -- SECURITY DEFINER allows reading auth.users despite RLS
  -- LIMIT 1 ensures single result (emails are unique in auth.users)
  RETURN QUERY
  SELECT au.id, au.email::TEXT
  FROM auth.users au
  WHERE au.email = p_email
  LIMIT 1;
END;
$$;

-- 2. handle_user_email_update: auth-side trigger on email change
CREATE OR REPLACE FUNCTION public.handle_user_email_update()
 RETURNS trigger
 LANGUAGE plpgsql
 SECURITY DEFINER SET search_path TO ''
AS $$
BEGIN
    -- Update public.users to match new email from auth.users
    -- Also update updated_at timestamp for audit trail
    UPDATE public.users
    SET email = NEW.email, updated_at = pg_catalog.now()
    WHERE id = NEW.id;

    -- Return NEW record (required for AFTER UPDATE triggers)
    RETURN NEW;
END;
$$;

-- 3. safe_decrypt_recovery_codes: fail-soft decryption on the secrets read path
CREATE OR REPLACE FUNCTION public.safe_decrypt_recovery_codes(encrypted_data text)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER SET search_path TO ''
AS $$
BEGIN
    -- NULL check: Return empty array if no encrypted data
    IF encrypted_data IS NULL THEN
        RETURN '[]'::jsonb;
    END IF;

    -- Try to decrypt and parse as JSON
    BEGIN
        -- Use decrypt_text which handles AES + base64 decoding
        -- Cast result to jsonb (validates JSON format)
        RETURN (public.decrypt_text(encrypted_data)::text)::jsonb;
    EXCEPTION
        WHEN OTHERS THEN
            -- If decryption fails (corrupt data, wrong key, invalid JSON, etc.),
            -- return empty array instead of crashing the query
            -- This allows secrets queries to succeed even if recovery codes are corrupt
            RETURN '[]'::jsonb;
    END;
END;
$$;
