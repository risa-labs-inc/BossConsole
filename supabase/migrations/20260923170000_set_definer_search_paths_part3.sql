-- Close the search_path on the five plugin-store SECURITY DEFINER RPCs the
-- part-1/part-2 family left unpinned (BossConsole#1165, part 3).
--
-- The sibling migrations 20260916130000 (BossConsole#773) and
-- 20260916140000 (BossConsole#772) pinned the passkey lifecycle set and
-- what part-2 called the last of the pre-convention functions; #845
-- landed that lineage. A full-catalog audit of current dev shows that
-- claim is off by exactly five plugin-store RPCs: after the entire
-- migration chain, 5 of public's 139 SECURITY DEFINER functions still
-- carry proconfig = NULL and resolve their table references through the
-- caller-influenced search_path (verified live in pg_proc, not from the
-- 2026-01/02 sources):
--
--   record_plugin_download(uuid, uuid, uuid, text)  INSERT public.plugin_downloads
--   upsert_plugin_rating(uuid, uuid, integer, text) upsert public.plugin_ratings
--   update_api_key_last_used(uuid)                  UPDATE public.plugin_api_keys
--   log_api_key_action(uuid, text, ...)              INSERT public.plugin_api_key_logs
--   get_user_api_key_count(uuid)                    read   public.plugin_api_keys
--
-- 20260803000000 re-pinned 15 other plugin-store RPCs and touched none
-- of these five - they were never redefined by any later CREATE,
-- ALTER or pinning migration (last-CREATE-wins audit across the chain).
-- All five are reachable from the plugin-store edge function
-- (utils/auth.ts, routes/api-keys.ts, services/ratings.ts,
-- services/downloads.ts) via service_role, i.e. authenticated client
-- traffic. With an unpinned search_path, a role that can create objects
-- in a schema it controls could shadow the unqualified table references
-- inside these definer bodies - the same privilege-escalation shape
-- part-1 closed.
--
-- Not a live exploit today (the honest scoping in 20260916130000
-- applies: exploiting a mutable search_path needs CREATE on a searched
-- schema, which the PostgREST roles lack). This closes the drift class
-- before a future CREATE grant or restored PUBLIC default could convert
-- any of these into escalation primitives, completing the family.
--
-- Each body below is the live pg_proc definition verbatim with two
-- mechanical changes only: the SET search_path TO '' clause, and schema
-- qualification of the table references (public.*) plus pg_catalog.now()
-- where the body called NOW(). Signatures, return types, logic, comments,
-- owners and grants are unchanged - the mutators were already
-- client-revoked (20260909130000, 20260910000000) and
-- get_user_api_key_count keeps its authenticated EXECUTE.
--
-- Nested-trigger due diligence (the 20260916130000 regression): the only
-- trigger on any table these five touch is trigger_check_api_key_limit,
-- a BEFORE INSERT FOR EACH ROW trigger on public.plugin_api_keys calling
-- check_api_key_limit() - which is not SECURITY DEFINER and so not part
-- of this family. None of the five INSERTs into public.plugin_api_keys
-- (update_api_key_last_used only UPDATEs it), so nothing pinned here
-- inherits an empty path through a trigger; the only statements executed
-- by a caller of these RPCs are the qualified ones below.

-- 1. record_plugin_download: analytics write on the download path
CREATE OR REPLACE FUNCTION public.record_plugin_download(p_plugin_id uuid, p_version_id uuid, p_user_id uuid DEFAULT NULL::uuid, p_ip_hash text DEFAULT NULL::text)
 RETURNS uuid
 LANGUAGE plpgsql
 SECURITY DEFINER SET search_path TO ''
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

-- 2. upsert_plugin_rating: create-or-update on the rating path
CREATE OR REPLACE FUNCTION public.upsert_plugin_rating(p_plugin_id uuid, p_user_id uuid, p_rating integer, p_review text DEFAULT ''::text)
 RETURNS TABLE(id uuid, created boolean)
 LANGUAGE plpgsql
 SECURITY DEFINER SET search_path TO ''
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

-- 3. update_api_key_last_used: last-used stamp on the auth path
CREATE OR REPLACE FUNCTION public.update_api_key_last_used(p_key_id uuid)
 RETURNS void
 LANGUAGE plpgsql
 SECURITY DEFINER SET search_path TO ''
AS $$
BEGIN
    UPDATE public.plugin_api_keys
    SET last_used_at = pg_catalog.now()
    WHERE id = p_key_id;
END;
$$;

-- 4. log_api_key_action: audit write on the API-key path
CREATE OR REPLACE FUNCTION public.log_api_key_action(p_api_key_id uuid, p_action text, p_plugin_id text DEFAULT NULL::text, p_ip_address text DEFAULT NULL::text, p_user_agent text DEFAULT NULL::text, p_success boolean DEFAULT true, p_error_message text DEFAULT NULL::text)
 RETURNS uuid
 LANGUAGE plpgsql
 SECURITY DEFINER SET search_path TO ''
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

-- 5. get_user_api_key_count: read-only counter keeping its client grant
CREATE OR REPLACE FUNCTION public.get_user_api_key_count(p_user_id uuid)
 RETURNS integer
 LANGUAGE plpgsql
 SECURITY DEFINER SET search_path TO ''
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
