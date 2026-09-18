-- Close the search_path on the five plugin-store SECURITY DEFINER functions
-- that the part-1 and part-2 sweeps did not reach (BossConsole#772, part 3).
--
-- 20260916130000 (BossConsole#773) closed the three passkey lifecycle
-- functions; 20260916140000 closed find_user_by_email,
-- handle_user_email_update and safe_decrypt_recovery_codes. A catalog-wide
-- audit of every CREATE FUNCTION in supabase/migrations now reports 146
-- SECURITY DEFINER functions: 134 already carry SET "search_path" TO '',
-- seven carry a deliberate non-empty path (decrypt_text/encrypt_text need
-- extensions, get_encryption_key needs vault, and the 20260908010000
-- visibility helpers name public), and these five carry no clause at all:
--
--   record_plugin_download(uuid, uuid, uuid, text)   inserts public.plugin_downloads
--   upsert_plugin_rating(uuid, uuid, integer, text)  updates/inserts public.plugin_ratings
--   log_api_key_action(uuid, text, text, text, text, boolean, text)
--                                                    inserts public.plugin_api_key_logs
--   update_api_key_last_used(uuid)                   updates public.plugin_api_keys
--   get_user_api_key_count(uuid)                     reads public.plugin_api_keys
--
-- All five predate the 20260802000000 convention and all five are live: the
-- plugin-store edge function calls them from services/downloads.ts,
-- services/ratings.ts, utils/auth.ts (twice) and routes/api-keys.ts.
--
-- Part 2's header warns that hardening from the original migration file can
-- collide with a later replacement, because several 20251023 functions were
-- rewritten by the org-support migrations. That does not apply here, and it
-- was checked rather than assumed: each of these five has exactly one
-- CREATE OR REPLACE FUNCTION in the whole migration set, in the file named
-- above. Every later mention is a GRANT, a REVOKE or a COMMENT.
--
-- Each body below is that definition with two mechanical changes only: the
-- SET "search_path" TO '' clause, and schema qualification of the table
-- references plus the unqualified NOW() in upsert_plugin_rating and
-- update_api_key_last_used. Signatures, return types, logic, owners and
-- grants are unchanged. CREATE OR REPLACE preserves the ACL, so the client
-- revokes already applied by 20260908030000, 20260909130000, 20260910000000,
-- 20260910120000 and 20260911010000 survive this migration; the pgTAP suite
-- pins that they do.
--
-- In UPDATE public.plugin_ratings the default alias is still plugin_ratings,
-- so the existing RETURNING plugin_ratings.id keeps resolving.
--
-- Not a live exploit today. The honest scoping of the sibling migrations
-- applies unchanged: turning a mutable search_path into privilege escalation
-- needs CREATE on a searched schema, which the PostgREST roles lack. This
-- closes the drift the repo's own convention and the Supabase linter demand,
-- before a future CREATE grant or a restored PUBLIC default could convert
-- any of these into privilege-escalation primitives.

CREATE OR REPLACE FUNCTION public.record_plugin_download(
    p_plugin_id UUID,
    p_version_id UUID,
    p_user_id UUID DEFAULT NULL,
    p_ip_hash TEXT DEFAULT NULL
)
RETURNS UUID
LANGUAGE plpgsql
SECURITY DEFINER
SET "search_path" TO ''
AS $$
DECLARE
    v_download_id UUID;
BEGIN
    INSERT INTO public.plugin_downloads (plugin_id, version_id, user_id, ip_hash)
    VALUES (p_plugin_id, p_version_id, p_user_id, p_ip_hash)
    RETURNING id INTO v_download_id;

    RETURN v_download_id;
END;
$$;

CREATE OR REPLACE FUNCTION public.upsert_plugin_rating(
    p_plugin_id UUID,
    p_user_id UUID,
    p_rating INT,
    p_review TEXT DEFAULT ''
)
RETURNS TABLE (
    id UUID,
    created BOOLEAN
)
LANGUAGE plpgsql
SECURITY DEFINER
SET "search_path" TO ''
AS $$
DECLARE
    v_rating_id UUID;
    v_created BOOLEAN;
BEGIN
    -- Check rating bounds
    IF p_rating < 1 OR p_rating > 5 THEN
        RAISE EXCEPTION 'Rating must be between 1 and 5';
    END IF;

    -- Try to update existing rating
    UPDATE public.plugin_ratings
    SET rating = p_rating, review = p_review, updated_at = pg_catalog.now()
    WHERE plugin_id = p_plugin_id AND user_id = p_user_id
    RETURNING plugin_ratings.id INTO v_rating_id;

    IF v_rating_id IS NOT NULL THEN
        v_created := false;
    ELSE
        -- Insert new rating
        INSERT INTO public.plugin_ratings (plugin_id, user_id, rating, review)
        VALUES (p_plugin_id, p_user_id, p_rating, p_review)
        RETURNING plugin_ratings.id INTO v_rating_id;
        v_created := true;
    END IF;

    RETURN QUERY SELECT v_rating_id, v_created;
END;
$$;

CREATE OR REPLACE FUNCTION public.update_api_key_last_used(p_key_id UUID)
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET "search_path" TO ''
AS $$
BEGIN
    UPDATE public.plugin_api_keys
    SET last_used_at = pg_catalog.now()
    WHERE id = p_key_id;
END;
$$;

CREATE OR REPLACE FUNCTION public.log_api_key_action(
    p_api_key_id UUID,
    p_action TEXT,
    p_plugin_id TEXT DEFAULT NULL,
    p_ip_address TEXT DEFAULT NULL,
    p_user_agent TEXT DEFAULT NULL,
    p_success BOOLEAN DEFAULT TRUE,
    p_error_message TEXT DEFAULT NULL
)
RETURNS UUID
LANGUAGE plpgsql
SECURITY DEFINER
SET "search_path" TO ''
AS $$
DECLARE
    v_log_id UUID;
BEGIN
    INSERT INTO public.plugin_api_key_logs (
        api_key_id, action, plugin_id, ip_address, user_agent, success, error_message
    )
    VALUES (
        p_api_key_id, p_action, p_plugin_id, p_ip_address, p_user_agent, p_success, p_error_message
    )
    RETURNING id INTO v_log_id;

    RETURN v_log_id;
END;
$$;

CREATE OR REPLACE FUNCTION public.get_user_api_key_count(p_user_id UUID)
RETURNS INTEGER
LANGUAGE plpgsql
SECURITY DEFINER
SET "search_path" TO ''
AS $$
DECLARE
    key_count INTEGER;
BEGIN
    SELECT COUNT(*) INTO key_count
    FROM public.plugin_api_keys
    WHERE user_id = p_user_id
    AND revoked_at IS NULL;

    RETURN key_count;
END;
$$;
