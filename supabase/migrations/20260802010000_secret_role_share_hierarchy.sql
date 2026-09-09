-- ============================================================================
-- BOSS Database Schema: Role-share visibility follows the role hierarchy
-- ============================================================================
-- File: 20260802010000_secret_role_share_hierarchy.sql
-- Description:
--   Fixes a pre-existing inconsistency: PERMISSION checks expand a user's roles
--   to their descendant closure, but SECRET ROLE SHARES do not.
--
--   Concretely, from 20260625000000 the hierarchy is
--       admin -> boss_admin -> user
--       admin -> finance_admin -> user
--   and authorize() expands each assigned role through get_role_descendants, so a
--   user holding `admin` effectively holds finance_admin's permissions. But
--   get_user_secrets_with_shared matches role shares with
--       shared_with_role_id IN (SELECT role_id FROM user_roles WHERE user_id = auth.uid())
--   -- assigned roles ONLY, no closure. So a secret shared with finance_admin is
--   invisible to an `admin`, even though that admin holds every finance_admin
--   permission. Two different answers to "who has role R's access" in one
--   database is itself the defect.
--
-- ############################################################################
-- ## THIS MIGRATION WIDENS READ ACCESS. IT IS AN ACCESS-REVIEW ITEM.         ##
-- ############################################################################
--   After it, a user holding a PARENT role gains visibility of secrets shared
--   with that role's descendants:
--     * `admin` gains secrets shared with boss_admin, finance_admin and user.
--     * an organisation admin gains secrets shared with their own organisation's
--       member and custom roles -- which is the intent for organisations.
--   It does NOT widen sideways: a finance_admin gains nothing from `admin`, and
--   nothing crosses between sibling branches.
--
--   Because `user` is a descendant of everything, a secret shared with the `user`
--   role is -- correctly, and already -- visible to everyone. Nobody should share
--   a secret with `user`; that is unchanged by this migration.
--
--   IF THE ACCESS REVIEW REJECTS THE WIDENING, DROP ONLY THIS FILE. Nothing in
--   20260801* or 20260802000000 or 20260803000000 depends on it. That isolation
--   is the whole reason it is a separate migration.
--
-- Dependencies:
--   - 20260625000000_role_hierarchy_and_granular_rbac.sql (get_role_descendants)
--   - 20260802000000_secrets_org_ownership.sql            (the functions edited)
--
-- Next migration: 20260803000000_plugins_org_ownership.sql
-- ============================================================================


-- ============================================================================
-- SECTION 1: The closure helpers
-- ============================================================================

-- get_role_ancestors: the role itself plus all transitive PARENTS. The mirror of
-- get_role_descendants. UNION (not UNION ALL) makes it cycle- and DAG-safe, the
-- same way its counterpart is.
--
-- Not used by the share fix itself -- that needs descendants -- but it is the
-- natural question "who inherits this role's access?" that an admin UI asks, and
-- having only one direction available is what led to the inconsistency above.
CREATE OR REPLACE FUNCTION "public"."get_role_ancestors"("p_role_id" "uuid")
RETURNS SETOF "uuid"
    LANGUAGE "sql" STABLE SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
    WITH RECURSIVE ancestors("role_id") AS (
        SELECT p_role_id
        UNION
        SELECT rh."parent_role_id"
        FROM public.role_hierarchy rh
        JOIN ancestors a ON rh."child_role_id" = a."role_id"
    )
    SELECT "role_id" FROM ancestors;
$$;

ALTER FUNCTION "public"."get_role_ancestors"("uuid") OWNER TO "postgres";

COMMENT ON FUNCTION "public"."get_role_ancestors"("uuid") IS 'The given role plus all transitive PARENT roles via role_hierarchy. Cycle- and DAG-safe. The mirror of get_role_descendants: use this to answer "who inherits this role''s access?".';

REVOKE EXECUTE ON FUNCTION "public"."get_role_ancestors"("uuid") FROM PUBLIC, "anon", "authenticated";
GRANT  EXECUTE ON FUNCTION "public"."get_role_ancestors"("uuid") TO "service_role";


-- effective_share_role_ids: the role set a user's role SHARES should match --
-- the descendant closure of their assigned roles. Exactly the expansion
-- authorize() performs for permissions, so the two now agree by construction.
CREATE OR REPLACE FUNCTION "public"."effective_share_role_ids"("p_user_id" "uuid")
RETURNS SETOF "uuid"
    LANGUAGE "sql" STABLE SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
    SELECT DISTINCT d."role_id"
    FROM public.user_roles ur
    CROSS JOIN LATERAL public.get_role_descendants(ur."role_id") AS d("role_id")
    WHERE ur."user_id" = p_user_id;
$$;

ALTER FUNCTION "public"."effective_share_role_ids"("uuid") OWNER TO "postgres";

COMMENT ON FUNCTION "public"."effective_share_role_ids"("uuid") IS 'Role ids whose secret shares a user should see: the descendant closure of their assigned roles, matching the expansion authorize() uses for permissions. ARBITRARY SUBJECT, so service_role only -- use my_effective_share_role_ids() from a policy.';

-- service_role only. The earlier comment claimed this was safe for authenticated
-- "because it is scoped to a single subject", but the subject is the PARAMETER and
-- nothing ties it to the caller: any authenticated user could ask for anyone
-- else's full descendant role closure, and public.roles is world-readable, so
-- those ids resolve straight to names. 20260625000000 SECTION 9 already wrote this
-- rule for the same family of helpers.
REVOKE EXECUTE ON FUNCTION "public"."effective_share_role_ids"("uuid")
    FROM PUBLIC, "anon", "authenticated";
GRANT  EXECUTE ON FUNCTION "public"."effective_share_role_ids"("uuid") TO "service_role";


-- The self-subject form, which is all the RLS policy ever needed.
CREATE OR REPLACE FUNCTION "public"."my_effective_share_role_ids"()
RETURNS SETOF "uuid"
    LANGUAGE "sql" STABLE SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
    SELECT public.effective_share_role_ids(auth.uid());
$$;

ALTER FUNCTION "public"."my_effective_share_role_ids"() OWNER TO "postgres";

COMMENT ON FUNCTION "public"."my_effective_share_role_ids"() IS
'The CALLER''s effective share-role closure. Subject is auth.uid(), never a parameter, which is what makes this safe to grant to authenticated. Used by the secret_shares SELECT policy.';

-- REVOKE first: 20251023000014_grants.sql sets ALTER DEFAULT PRIVILEGES ... GRANT ALL ON
-- FUNCTIONS TO anon, so every function here is anon-executable the moment it is created
-- and a bare GRANT to authenticated does not take that away. Same trap as ON TABLES,
-- caught there and missed here. None of these were an authorization hole - they all
-- resolve through resolve_org_actor, which returns NULL for anon - but an
-- unauthenticated DB-reachable surface nobody intended is worth closing.
REVOKE EXECUTE ON FUNCTION "public"."my_effective_share_role_ids"() FROM PUBLIC, "anon";
GRANT EXECUTE ON FUNCTION "public"."my_effective_share_role_ids"() TO "authenticated", "service_role";


-- ============================================================================
-- SECTION 2: Apply the closure in the three places role shares are matched
-- ============================================================================
-- Body-only changes: signatures and return types are identical to
-- 20260802000000, so CREATE OR REPLACE is correct and grants are preserved.

-- (a) can_access_secret -- the shared read predicate.
CREATE OR REPLACE FUNCTION "public"."can_access_secret"("p_secret_id" "uuid")
RETURNS boolean
    LANGUAGE "sql" STABLE SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
    SELECT EXISTS (
        SELECT 1 FROM public.secrets s
        WHERE s.id = p_secret_id
          AND ( s.user_id = auth.uid()
             OR (s.org_id IS NOT NULL AND public.is_org_member(s.org_id)) )
    ) OR EXISTS (
        SELECT 1 FROM public.secret_shares ss
        WHERE ss.secret_id = p_secret_id
          AND (ss.expires_at IS NULL OR ss.expires_at > now())
          AND ( ss.shared_with_user_id = auth.uid()
             -- 20260802010000: descendant closure, not assigned roles only.
             OR ss.shared_with_role_id IN (SELECT public.effective_share_role_ids(auth.uid()))
             OR (ss.shared_with_org_id IS NOT NULL AND public.is_org_member(ss.shared_with_org_id)) )
    );
$$;

ALTER FUNCTION "public"."can_access_secret"("uuid") OWNER TO "postgres";


-- (b) get_user_secrets_with_shared -- source 3, the role-share arm.
--     Reproduced in full from 20260802000000 with that one predicate changed.
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
    ORDER BY s.created_at DESC
    LIMIT p_limit OFFSET p_offset;
END;
$$;

ALTER FUNCTION "public"."get_user_secrets_with_shared"(integer,integer) OWNER TO "postgres";


-- (c) The secret_shares SELECT policy -- so direct PostgREST reads agree with the
--     RPC. Leaving this one behind is how the two paths silently diverge.
DROP POLICY IF EXISTS "secret_shares_select" ON "public"."secret_shares";
CREATE POLICY "secret_shares_select" ON "public"."secret_shares"
    FOR SELECT USING (
        "public"."can_manage_secret"("secret_id")
        OR "shared_with_user_id" = "auth"."uid"()
        OR "shared_with_role_id" IN (SELECT "public"."my_effective_share_role_ids"())
        OR ("shared_with_org_id" IS NOT NULL AND "public"."is_org_member"("shared_with_org_id"))
    );


-- ============================================================================
-- End of File: 20260802010000_secret_role_share_hierarchy.sql
-- ============================================================================
-- Next Migration: 20260803000000_plugins_org_ownership.sql
-- ============================================================================
