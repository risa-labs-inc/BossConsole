-- ============================================================================
-- BOSS Database Schema: Resilient Secret Listing (one bad row must not blank a page)
-- ============================================================================
-- File: 20260914000000_resilient_secret_listing.sql
-- Description: get_user_secrets, search_user_secrets and get_user_secrets_with_shared
--   decrypted the password (and recovery codes) with a bare public.decrypt_text
--   inside a set-returning query. decrypt_text RAISES on an undecryptable value -
--   a wrong key after a partial rotation, storage corruption, or a v2 MAC
--   mismatch - and one raised row aborts the WHOLE query, so a single bad row
--   made every one of the caller's secrets fail to load, not just the bad one.
--   twofa_secret was already read through safe_decrypt_twofa_secret (NULL on
--   failure); recovery codes and passwords were not.
--
--   This adds public.try_decrypt_text(text): decrypt_text wrapped so any failure
--   yields NULL for that one field, exactly as safe_decrypt_recovery_codes yields
--   [] for a bad recovery-codes cell. The three listing RPCs now read the
--   password through it and the recovery codes through the existing
--   safe_decrypt_recovery_codes, so a single corrupt row surfaces as one secret
--   with a null password / empty codes while every other secret still loads.
--
--   NOT named safe_decrypt_*: the key-rotation script
--   (supabase/ops/rotate_master_encryption_key.sql) treats every public
--   safe_decrypt_% function as a bespoke-format read path that MUST be mapped in
--   its column list, and refuses to run if one is not. try_decrypt_text is a
--   generic resilient reader for an ordinary column, not a new storage format,
--   so it deliberately avoids that prefix and needs no rotation adapter.
--
--   The three functions are restated verbatim from their current definitions
--   (get_user_secrets / search_user_secrets from 20260907000000,
--   get_user_secrets_with_shared from 20260909000000) with ONLY the two decrypt
--   calls changed; every authorization source, the paging tiebreaker and the
--   RETURNS TABLE shape are unchanged. Read authority is unchanged - only what a
--   reader already entitled to a row sees when that row cannot be decrypted. The
--   failure is now silent per row: monitor for a null password on a non-null
--   password_encrypted, and note rotation still fails closed on the same rows
--   because it reads through decrypt_text, not this wrapper.
-- ============================================================================

-- Function: try_decrypt_text - decrypt_text, but NULL instead of RAISE on failure.
CREATE OR REPLACE FUNCTION "public"."try_decrypt_text"("ciphertext" "text") RETURNS "text"
    LANGUAGE "plpgsql" STABLE SECURITY DEFINER
    SET "search_path" TO 'public, pg_catalog'
    AS $BODY$
BEGIN
    IF ciphertext IS NULL THEN
        RETURN NULL;
    END IF;
    BEGIN
        RETURN public.decrypt_text(ciphertext);
    EXCEPTION
        WHEN OTHERS THEN
            -- Corrupt data, a wrong key mid-rotation, or a v2 integrity failure:
            -- blank this one field rather than abort the caller's whole listing.
            RETURN NULL;
    END;
END;
$BODY$;

ALTER FUNCTION "public"."try_decrypt_text"("ciphertext" "text") OWNER TO "postgres";
COMMENT ON FUNCTION "public"."try_decrypt_text"("ciphertext" "text") IS
    'decrypt_text that returns NULL instead of raising, so one undecryptable row does not abort a secret listing. Not a safe_decrypt_* rotation adapter.';
-- Same client posture as decrypt_text (20260909120000): called only inside these
-- SECURITY DEFINER RPCs, never directly by a client.
REVOKE ALL ON FUNCTION "public"."try_decrypt_text"("ciphertext" "text") FROM PUBLIC, anon, authenticated;


-- (a) get_user_secrets - restated from 20260907000000; decrypt calls only.
CREATE OR REPLACE FUNCTION "public"."get_user_secrets"(
    "p_limit" integer DEFAULT 50,
    "p_offset" integer DEFAULT 0
) RETURNS TABLE(
    "id" "uuid", "website" "text", "username" "text", "password" "text", "notes" "text",
    "expiration_date" timestamp with time zone, "tags" "jsonb", "metadata" "jsonb",
    "created_at" timestamp with time zone, "updated_at" timestamp with time zone,
    "org_id" "uuid", "org_slug" "text", "is_org_owned" boolean, "can_manage" boolean
)
    LANGUAGE "plpgsql" STABLE SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
BEGIN
    RETURN QUERY
    SELECT
        s.id, s.website, s.username,
        public.try_decrypt_text(s.password_encrypted) AS password,
        s.notes, s.expiration_date,
        COALESCE((SELECT jsonb_agg(st.tag) FROM public.secret_tags st WHERE st.secret_id = s.id), '[]'::jsonb) AS tags,
        COALESCE((
            SELECT jsonb_build_object(
                'twofa_enabled', sm.twofa_enabled,
                'twofa_type', sm.twofa_type,
                'recovery_codes', public.safe_decrypt_recovery_codes(sm.recovery_codes_encrypted)
            )
            FROM public.secret_metadata sm WHERE sm.secret_id = s.id
        ), '{}'::jsonb) AS metadata,
        s.created_at, s.updated_at,
        s.org_id,
        o.slug AS org_slug,
        (s.org_id IS NOT NULL) AS is_org_owned,
        (s.user_id = auth.uid()
            OR public.is_user_admin(auth.uid())
            OR (s.org_id IS NOT NULL AND public.is_org_admin(s.org_id))) AS can_manage
    FROM public.secrets s
    LEFT JOIN public.organisations o ON o.id = s.org_id
    -- Widened from "own secrets" to "own secrets + organisation secrets I can see".
    WHERE s.user_id = auth.uid()
       OR (s.org_id IS NOT NULL AND public.is_org_member(s.org_id))
    ORDER BY s.created_at DESC, s.id DESC
    LIMIT p_limit OFFSET p_offset;
END;
$$;

ALTER FUNCTION "public"."get_user_secrets"(integer,integer) OWNER TO "postgres";

ALTER FUNCTION "public"."get_user_secrets"(integer,integer) OWNER TO "postgres";


-- (b) search_user_secrets - restated from 20260907000000; decrypt calls only.
CREATE OR REPLACE FUNCTION "public"."search_user_secrets"(
    "p_query" "text",
    "p_limit" integer DEFAULT 50,
    "p_offset" integer DEFAULT 0
) RETURNS TABLE(
    "id" "uuid", "website" "text", "username" "text", "password" "text", "notes" "text",
    "expiration_date" timestamp with time zone, "tags" "jsonb", "metadata" "jsonb",
    "created_at" timestamp with time zone, "updated_at" timestamp with time zone,
    "org_id" "uuid", "org_slug" "text", "is_org_owned" boolean, "can_manage" boolean
)
    LANGUAGE "plpgsql" STABLE SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
BEGIN
    RETURN QUERY
    SELECT
        s.id, s.website, s.username,
        public.try_decrypt_text(s.password_encrypted) AS password,
        s.notes, s.expiration_date,
        COALESCE((SELECT jsonb_agg(st.tag) FROM public.secret_tags st WHERE st.secret_id = s.id), '[]'::jsonb) AS tags,
        COALESCE((
            SELECT jsonb_build_object(
                'twofa_enabled', sm.twofa_enabled,
                'twofa_type', sm.twofa_type,
                'recovery_codes', public.safe_decrypt_recovery_codes(sm.recovery_codes_encrypted)
            )
            FROM public.secret_metadata sm WHERE sm.secret_id = s.id
        ), '{}'::jsonb) AS metadata,
        s.created_at, s.updated_at,
        s.org_id,
        o.slug AS org_slug,
        (s.org_id IS NOT NULL) AS is_org_owned,
        (s.user_id = auth.uid()
            OR public.is_user_admin(auth.uid())
            OR (s.org_id IS NOT NULL AND public.is_org_admin(s.org_id))) AS can_manage
    FROM public.secrets s
    LEFT JOIN public.organisations o ON o.id = s.org_id
    WHERE (s.user_id = auth.uid()
           OR (s.org_id IS NOT NULL AND public.is_org_member(s.org_id)))
      AND (s.website ILIKE '%' || p_query || '%' OR s.username ILIKE '%' || p_query || '%')
    ORDER BY s.created_at DESC, s.id DESC
    LIMIT p_limit OFFSET p_offset;
END;
$$;

ALTER FUNCTION "public"."search_user_secrets"("text",integer,integer) OWNER TO "postgres";

ALTER FUNCTION "public"."search_user_secrets"("text",integer,integer) OWNER TO "postgres";


-- (c) get_user_secrets_with_shared - restated from 20260909000000; decrypt calls only.
CREATE OR REPLACE FUNCTION "public"."get_user_secrets_with_shared"(
    "p_limit" integer DEFAULT 50,
    "p_offset" integer DEFAULT 0
) RETURNS TABLE(
    "id" "uuid", "website" "text", "username" "text", "password" "text", "notes" "text",
    "expiration_date" timestamp with time zone, "tags" "jsonb", "metadata" "jsonb",
    "created_at" timestamp with time zone, "updated_at" timestamp with time zone,
    "is_owner" boolean, "shared_by_email" "text", "access_level" "text",
    "org_id" "uuid", "org_slug" "text", "is_org_owned" boolean,
    "shared_with_org_slug" "text", "can_manage" boolean
)
    LANGUAGE "plpgsql" STABLE SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
BEGIN
    RETURN QUERY
    WITH accessible_secrets AS (
        -- Source 1: the caller's own secrets.
        SELECT s.id, TRUE AS is_owner, NULL::TEXT AS shared_by_email,
               'owner'::TEXT AS access_level, NULL::TEXT AS shared_with_org_slug, 1 AS priority
        FROM public.secrets s
        WHERE s.user_id = auth.uid()

        UNION ALL

        -- Source 4: secrets OWNED BY an organisation the caller belongs to.
        SELECT s.id, (s.user_id = auth.uid()) AS is_owner, NULL::TEXT,
               'org'::TEXT, o.slug, 2
        FROM public.secrets s
        JOIN public.organisations o ON o.id = s.org_id
        WHERE s.org_id IS NOT NULL
          AND public.is_org_member(s.org_id)

        UNION ALL

        -- Source 2: shared directly with the caller.
        SELECT s.id, FALSE, u.email, ss.access_level, NULL::TEXT, 3
        FROM public.secrets s
        JOIN public.secret_shares ss ON ss.secret_id = s.id
        JOIN auth.users u ON u.id = ss.shared_by
        WHERE ss.shared_with_user_id = auth.uid()
          AND (ss.expires_at IS NULL OR ss.expires_at > now())

        UNION ALL

        -- Source 3: shared with a role the caller holds, OR any DESCENDANT of one
        -- (20260802010000). Previously matched assigned roles only, which
        -- disagreed with how authorize() expands permissions.
        SELECT s.id, FALSE, u.email, ss.access_level, NULL::TEXT, 4
        FROM public.secrets s
        JOIN public.secret_shares ss ON ss.secret_id = s.id
        JOIN auth.users u ON u.id = ss.shared_by
        WHERE ss.shared_with_role_id IN (SELECT public.effective_share_role_ids(auth.uid()))
          AND (ss.expires_at IS NULL OR ss.expires_at > now())

        UNION ALL

        -- Source 5: shared with an organisation the caller belongs to.
        SELECT s.id, FALSE, u.email, ss.access_level, o.slug, 5
        FROM public.secrets s
        JOIN public.secret_shares ss ON ss.secret_id = s.id
        JOIN auth.users u ON u.id = ss.shared_by
        JOIN public.organisations o ON o.id = ss.shared_with_org_id
        WHERE ss.shared_with_org_id IS NOT NULL
          AND public.is_org_member(ss.shared_with_org_id)
          AND (ss.expires_at IS NULL OR ss.expires_at > now())
    ),
    unique_secrets AS (
        SELECT DISTINCT ON (a.id)
            a.id, a.is_owner, a.shared_by_email, a.access_level, a.shared_with_org_slug
        FROM accessible_secrets a
        ORDER BY a.id, a.is_owner DESC, a.priority
    )
    SELECT
        s.id, s.website, s.username,
        public.try_decrypt_text(s.password_encrypted) AS password,
        s.notes, s.expiration_date,
        COALESCE((SELECT jsonb_agg(st.tag) FROM public.secret_tags st WHERE st.secret_id = s.id), '[]'::jsonb) AS tags,
        COALESCE((
            SELECT jsonb_build_object(
                'twofa_enabled', sm.twofa_enabled,
                'twofa_type', sm.twofa_type,
                'twofa_secret', public.safe_decrypt_twofa_secret(sm.twofa_secret),
                'recovery_codes', public.safe_decrypt_recovery_codes(sm.recovery_codes_encrypted)
            )
            FROM public.secret_metadata sm WHERE sm.secret_id = s.id
        ), '{}'::jsonb) AS metadata,
        s.created_at, s.updated_at,
        us.is_owner, us.shared_by_email, us.access_level,
        s.org_id,
        o.slug AS org_slug,
        (s.org_id IS NOT NULL) AS is_org_owned,
        us.shared_with_org_slug,
        (s.user_id = auth.uid()
            OR public.is_user_admin(auth.uid())
            OR (s.org_id IS NOT NULL AND public.is_org_admin(s.org_id))) AS can_manage
    FROM unique_secrets us
    JOIN public.secrets s ON s.id = us.id
    LEFT JOIN public.organisations o ON o.id = s.org_id
    ORDER BY s.created_at DESC, s.id DESC
    LIMIT p_limit OFFSET p_offset;
END;
$$;

ALTER FUNCTION "public"."get_user_secrets_with_shared"(integer,integer) OWNER TO "postgres";

ALTER FUNCTION "public"."get_user_secrets_with_shared"(integer,integer) OWNER TO "postgres";


-- Restate the existing RPC grants, as the paging and TOTP migrations do.
GRANT EXECUTE ON FUNCTION "public"."get_user_secrets"(integer,integer)               TO "authenticated", "service_role";
GRANT EXECUTE ON FUNCTION "public"."search_user_secrets"("text",integer,integer)     TO "authenticated", "service_role";
GRANT EXECUTE ON FUNCTION "public"."get_user_secrets_with_shared"(integer,integer)   TO "authenticated", "service_role";

-- ============================================================================
-- End of File: 20260914000000_resilient_secret_listing.sql
-- ============================================================================
