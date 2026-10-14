-- Close the search_path on the five functions that are not SECURITY DEFINER.
--
-- The #772 sweep closes `SET search_path` on SECURITY DEFINER functions:
-- 20260916130000 and 20260916140000 are merged, and #1171 is the part for the
-- five plugin-store ones. Supabase's advisor (lint 0011,
-- function_search_path_mutable) does not scope itself that way: it reports
-- every function in an exposed schema with a mutable search_path, and against a
-- migrated database it returns ten. Five are the definer functions #1171 closes.
-- These are the other five, and every one of them is SECURITY INVOKER, which is
-- why a definer-scoped audit did not list them.
--
-- Read from the catalog rather than the migration sources, because grep over
-- the SQL files is not a reliable answer here. While auditing this it gave three
-- wrong counts: two for RLS coverage, from quoted identifiers and then from
-- aligned whitespace, and one for this very question, from functions redefined
-- in a later migration. The catalog was right each time.
--
--   proname                                    prosecdef  provolatile  proconfig
--   check_api_key_limit                        f          v            (none)
--   cleanup_expired_completed_authentications  f          v            (none)
--   custom_access_token_hook                   f          s            (none)
--   trigger_cleanup_expired_completed_auths    f          v            (none)
--   update_plugin_timestamp                    f          v            (none)
--
-- SECURITY INVOKER is a weaker exposure than DEFINER, and this migration does
-- not claim otherwise: the function runs as the caller, so a mutable path
-- cannot lend the caller privileges it does not already hold. Two things still
-- make it worth closing.
--
-- The first is that `custom_access_token_hook` does not run as an ordinary
-- caller. GoTrue invokes it as `supabase_auth_admin` on every token issuance,
-- and it decides the `is_admin`, `user_role` and `user_permissions` claims. Its
-- search_path is therefore whatever that role carries, and the function is the
-- one place in this schema where an unqualified name would be resolved with a
-- privileged role's path while deciding an authorization claim.
--
-- The second is drift. 134 definer functions carry the clause, and two merged
-- migrations have swept the stragglers since; leaving five without it
-- means the advisor never reads clean, and a lint nobody expects to be empty
-- stops being read at all.
--
-- The bodies are carried over verbatim apart from two mechanical changes, the
-- clause and schema qualification. Unqualified names that remain are all
-- pg_catalog builtins (`jsonb_set`, `to_jsonb`, `array_length`, `coalesce`),
-- which stay reachable with an empty search_path because pg_catalog is searched
-- implicitly. The hook's three project calls were already qualified.
--
-- Signatures, return types, volatility and security mode are unchanged, and
-- CREATE OR REPLACE preserves the ACL, so the grants that 20260908030000 and
-- 20260909130000 settled on these functions survive.

-- ---------------------------------------------------------------------------
-- plugin_api_keys -> public.plugin_api_keys
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION "public"."check_api_key_limit"()
    RETURNS trigger
    LANGUAGE "plpgsql"
    SET "search_path" TO ''
    AS $$
DECLARE
    active_key_count INTEGER;
    max_keys_per_user CONSTANT INTEGER := 10;
BEGIN
    -- Count active (non-revoked) keys for this user
    SELECT COUNT(*) INTO active_key_count
    FROM public.plugin_api_keys
    WHERE user_id = NEW.user_id
    AND revoked_at IS NULL;

    -- Check limit (count is before insert, so >= means we'd exceed)
    IF active_key_count >= max_keys_per_user THEN
        RAISE EXCEPTION 'API key limit exceeded. Maximum % active keys per user allowed.', max_keys_per_user
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NEW;
END;
$$;

-- ---------------------------------------------------------------------------
-- completed_authentications -> public.completed_authentications, NOW() ->
-- pg_catalog.now()
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION "public"."cleanup_expired_completed_authentications"()
    RETURNS void
    LANGUAGE "plpgsql"
    SET "search_path" TO ''
    AS $$
BEGIN
  -- Delete authentication results past their expiration
  -- Typically expires_at_timestamp = completed_at + 2 minutes
  DELETE FROM public.completed_authentications
  WHERE expires_at_timestamp < pg_catalog.now();
END;
$$;

-- ---------------------------------------------------------------------------
-- completed_authentications -> public.completed_authentications, random() and
-- NOW() -> pg_catalog.*
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION "public"."trigger_cleanup_expired_completed_auths"()
    RETURNS trigger
    LANGUAGE "plpgsql"
    SET "search_path" TO ''
    AS $$
BEGIN
  -- Probabilistic cleanup: Only run 10% of the time
  -- Reduces overhead while keeping table size manageable
  IF pg_catalog.random() < 0.1 THEN
    -- Delete expired authentication results (expires_at_timestamp < NOW())
    -- Fast via index on expires_at_timestamp column
    DELETE FROM public.completed_authentications
    WHERE expires_at_timestamp < pg_catalog.now();
  END IF;

  -- Return NEW record (required for AFTER INSERT triggers)
  RETURN NEW;
END;
$$;

-- ---------------------------------------------------------------------------
-- plugins -> public.plugins, NOW() -> pg_catalog.now()
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION "public"."update_plugin_timestamp"()
    RETURNS trigger
    LANGUAGE "plpgsql"
    SET "search_path" TO ''
    AS $$
BEGIN
    UPDATE public.plugins SET updated_at = pg_catalog.now() WHERE id = NEW.plugin_id;
    RETURN NEW;
END;
$$;

-- ---------------------------------------------------------------------------
-- The auth hook. Body unchanged: its three project calls were already
-- schema-qualified, and everything else it names is a pg_catalog builtin.
-- STABLE is carried over; dropping it would change how the planner may cache
-- the call.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION "public"."custom_access_token_hook"("event" "jsonb")
    RETURNS "jsonb"
    LANGUAGE "plpgsql" STABLE
    SET "search_path" TO ''
    AS $$
DECLARE
    claims jsonb;
    user_roles_array text[];
    user_perms_array text[];
    primary_role text;
    v_orgs jsonb;
    v_user_id uuid := (event->>'user_id')::uuid;
BEGIN
    claims := event->'claims';

    user_roles_array := public.get_user_roles_for_hook(v_user_id);
    user_perms_array := public.get_effective_permissions(v_user_id);

    IF user_roles_array IS NOT NULL AND array_length(user_roles_array, 1) > 0 THEN
        primary_role := user_roles_array[1];
    ELSE
        primary_role := 'user';
    END IF;

    IF user_roles_array IS NOT NULL THEN
        claims := jsonb_set(claims, '{user_role}', to_jsonb(primary_role));
        claims := jsonb_set(claims, '{user_roles}', to_jsonb(user_roles_array));
        IF 'admin' = ANY(user_roles_array) THEN
            claims := jsonb_set(claims, '{is_admin}', to_jsonb(true));
        ELSE
            claims := jsonb_set(claims, '{is_admin}', to_jsonb(false));
        END IF;
    ELSE
        claims := jsonb_set(claims, '{user_role}', to_jsonb('user'::text));
        claims := jsonb_set(claims, '{user_roles}', to_jsonb(ARRAY['user']::text[]));
        claims := jsonb_set(claims, '{is_admin}', to_jsonb(false));
    END IF;

    -- Effective permissions (own + inherited via the role hierarchy)
    claims := jsonb_set(claims, '{user_permissions}', to_jsonb(COALESCE(user_perms_array, ARRAY[]::text[])));

    -- Organisation membership (20260801060000). UI HINTS ONLY, stale for up to
    -- jwt_expiry (3600s), so no policy and no RPC may authorize on them.
    -- get_user_orgs_for_hook never raises; the COALESCEs are a second belt in case
    -- the function is ever missing entirely.
    v_orgs := public.get_user_orgs_for_hook(v_user_id);
    claims := jsonb_set(claims, '{orgs}',      COALESCE(v_orgs -> 'orgs',      '[]'::jsonb));
    claims := jsonb_set(claims, '{org_admin}', COALESCE(v_orgs -> 'org_admin', '[]'::jsonb));

    event := jsonb_set(event, '{claims}', claims);
    RETURN event;
END;
$$;
