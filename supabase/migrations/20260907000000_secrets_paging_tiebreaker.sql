-- ============================================================================
-- BOSS Database Schema: deterministic paging order for the secret listings
--
-- File: 20260907000000_secrets_paging_tiebreaker.sql
--
-- THE BUG: three RPCs page with
--     ORDER BY s.created_at DESC LIMIT p_limit OFFSET p_offset
-- and no tiebreaker. `secrets.created_at` is `timestamptz DEFAULT now() NOT NULL`
-- (20251023000010_secret_tables.sql), it carries no unique constraint and no
-- index, and `now()` is the TRANSACTION clock - so every secret written inside
-- one transaction, or by one import, shares a created_at byte for byte.
--
-- A sort key with ties is only a PARTIAL order. Postgres is free to emit tied
-- rows in whatever order the chosen plan produces, and that order is not stable
-- between two calls: a different plan, a different worker count, or simply a
-- different heap read order can permute them. LIMIT/OFFSET then slices that
-- unstable sequence. A row that was position 50 (last of page 1) on the first
-- call can be position 49 on the second, which is inside page 1's slice that the
-- caller has already consumed and before page 2's OFFSET - so a full walk of the
-- table returns it ZERO times, and some other tied row twice. Nothing errors;
-- the caller just sees a secret that exists as a secret that does not.
--
-- This is not theoretical. A plugin MCP tool walks get_user_secrets_with_shared
-- by offset looking for a secret by id, and a skipped row makes it report "no
-- secret with id X" for a secret sitting in the table.
--
-- get_user_secrets_with_shared is the worst of the three: it is a five-arm
-- UNION ALL collapsed by DISTINCT ON (a.id) and then re-joined to public.secrets,
-- so even the row ORDER reaching the final sort is planner-dependent before the
-- tie is broken.
--
-- THE FIX: append `, s.id DESC` to the outer ORDER BY of all three. `secrets.id`
-- is the primary key, so it is unique and NOT NULL by construction, which makes
-- (created_at DESC, id DESC) a TOTAL order over the result set. A total sort key
-- is what LIMIT/OFFSET paging requires to be self-consistent: every row has
-- exactly one position, and the same query returns the same sequence, so page
-- boundaries line up and no row can fall between them. This repo already applied
-- the same reasoning in 20260307000002_fix_views_security_invoker.sql, which
-- added `pv.id DESC` to plugins_with_latest_version for the same class of defect.
--
-- WHAT THIS DOES NOT CHANGE: nothing but the order of rows that were already
-- tied. The signatures, the RETURNS TABLE column lists, the filters, the joins,
-- the decryption, the DISTINCT ON precedence, SECURITY DEFINER, `SET search_path
-- TO ''`, the owner and the grants are all reproduced verbatim. The bodies below
-- were extracted from the migrations named against each function and the only
-- edited line in each is its ORDER BY.
--
-- CREATE OR REPLACE, NOT AN EDIT TO THE 20260802* FILES. Those are applied on
-- production; editing them would drift the recorded checksum and would have no
-- effect anywhere they had already run while looking authoritative in the source.
-- Replacing a function body requires the WHOLE definition, hence the repetition.
--
-- NO INDEX HERE, DELIBERATELY. `(created_at DESC, id DESC)` on public.secrets
-- would let the planner satisfy this sort from an index instead of sorting the
-- whole accessible set per page. That is a worthwhile follow-up, but adding an
-- index to a production table is a write-amplification and lock-behaviour change
-- with its own risk profile, and it is not needed for CORRECTNESS - the ORDER BY
-- is total with or without it. Correctness ships alone.
--
-- Dependencies:
--   20260802000000_secrets_org_ownership.sql      (get_user_secrets, search_user_secrets)
--   20260802010000_secret_role_share_hierarchy.sql (get_user_secrets_with_shared)
-- ============================================================================


-- ----------------------------------------------------------------------------
-- (a) get_user_secrets - verbatim from 20260802000000, ORDER BY only.
-- ----------------------------------------------------------------------------
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
        public.decrypt_text(s.password_encrypted) AS password,
        s.notes, s.expiration_date,
        COALESCE((SELECT jsonb_agg(st.tag) FROM public.secret_tags st WHERE st.secret_id = s.id), '[]'::jsonb) AS tags,
        COALESCE((
            SELECT jsonb_build_object(
                'twofa_enabled', sm.twofa_enabled,
                'twofa_type', sm.twofa_type,
                'recovery_codes', CASE WHEN sm.recovery_codes_encrypted IS NOT NULL
                    THEN public.decrypt_text(sm.recovery_codes_encrypted)::jsonb ELSE '[]'::jsonb END
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


-- ----------------------------------------------------------------------------
-- (b) search_user_secrets - verbatim from 20260802000000, ORDER BY only.
-- ----------------------------------------------------------------------------
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
        public.decrypt_text(s.password_encrypted) AS password,
        s.notes, s.expiration_date,
        COALESCE((SELECT jsonb_agg(st.tag) FROM public.secret_tags st WHERE st.secret_id = s.id), '[]'::jsonb) AS tags,
        COALESCE((
            SELECT jsonb_build_object(
                'twofa_enabled', sm.twofa_enabled,
                'twofa_type', sm.twofa_type,
                'recovery_codes', CASE WHEN sm.recovery_codes_encrypted IS NOT NULL
                    THEN public.decrypt_text(sm.recovery_codes_encrypted)::jsonb ELSE '[]'::jsonb END
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


-- ----------------------------------------------------------------------------
-- (c) get_user_secrets_with_shared - verbatim from 20260802010000 (which is the
--     current definition; 20260802000000's earlier one was superseded there),
--     ORDER BY only.
-- ----------------------------------------------------------------------------
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
        public.decrypt_text(s.password_encrypted) AS password,
        s.notes, s.expiration_date,
        COALESCE((SELECT jsonb_agg(st.tag) FROM public.secret_tags st WHERE st.secret_id = s.id), '[]'::jsonb) AS tags,
        COALESCE((
            SELECT jsonb_build_object(
                'twofa_enabled', sm.twofa_enabled,
                'twofa_type', sm.twofa_type,
                'twofa_secret', sm.twofa_secret,
                'recovery_codes', CASE WHEN sm.recovery_codes_encrypted IS NOT NULL
                    THEN public.decrypt_text(sm.recovery_codes_encrypted)::jsonb ELSE '[]'::jsonb END
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


-- ----------------------------------------------------------------------------
-- Grants restated verbatim from 20260802000000 SECTION 7. CREATE OR REPLACE
-- preserves the existing ACL and does not re-apply default privileges, so these
-- are no-ops on any database where those migrations ran. They are here so the
-- privilege set a reader must hold to call these functions is visible in the
-- same file that rewrites them, and re-granting an already-held privilege
-- changes nothing. No REVOKE: this migration widens and narrows nothing.
-- ----------------------------------------------------------------------------
GRANT EXECUTE ON FUNCTION "public"."get_user_secrets"(integer,integer)                              TO "authenticated", "service_role";
GRANT EXECUTE ON FUNCTION "public"."search_user_secrets"("text",integer,integer)                    TO "authenticated", "service_role";
GRANT EXECUTE ON FUNCTION "public"."get_user_secrets_with_shared"(integer,integer)                  TO "authenticated", "service_role";


-- ============================================================================
-- End of File: 20260907000000_secrets_paging_tiebreaker.sql
-- ============================================================================
