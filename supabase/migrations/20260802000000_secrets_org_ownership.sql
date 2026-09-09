-- ============================================================================
-- BOSS Database Schema: Organisation-owned secrets
-- ============================================================================
-- File: 20260802000000_secrets_org_ownership.sql
-- Description:
--   Lets a secret be owned by an ORGANISATION rather than a person, and adds an
--   organisation as a share target alongside the existing user and role targets.
--
--   Why org ownership at all: today every secret has a single human owner, so an
--   organisation's shared credentials die with that person's account -- offboard
--   the one engineer who created the deploy key and the ON DELETE CASCADE takes
--   it with them. An org-owned secret survives, and its lifecycle is the
--   organisation's.
--
-- Dependencies:
--   - 20251023000004_secret_functions.sql   (the nine functions recreated here)
--   - 20251023000010_secret_tables.sql      (secrets, secret_shares, ...)
--   - 20251023000013_rls_policies.sql       (the policies replaced here)
--   - 20251023000014_grants.sql             (the grants re-issued here)
--   - 20260801010000 / 20260801070000       (is_org_member, is_org_admin, boss org)
--
-- MUST RUN AFTER 20260801070000 (the boss organisation seed).
--
-- ############################################################################
-- ## THREE HAZARDS IN THIS FILE                                             ##
-- ############################################################################
--
-- (1) SIGNATURE AND RETURN-TYPE CHANGES REQUIRE DROP, NOT REPLACE.
--     CREATE OR REPLACE with a different argument list creates a SECOND
--     OVERLOAD; a call with the old argument set then fails with
--     "function is not unique" and the Secret Manager plugin breaks for
--     everyone. CREATE OR REPLACE also cannot change a RETURNS TABLE shape at
--     all. Precedent: 20260630000000 did exactly this DROP-then-recreate dance
--     for get_plugin_with_stats. Every DROP below is paired with a re-GRANT.
--
-- (2) THE ORIGINALS' search_path IS INCONSISTENT, AND THREE HAVE NONE.
--     share_secret, unshare_secret and get_secret_shares are SECURITY DEFINER
--     with NO `SET search_path`, which is a textbook definer-function
--     escalation surface: a caller who can create objects in an earlier schema
--     on the search path can shadow an unqualified name inside a function
--     running as postgres. All nine are recreated here with
--     `SET search_path TO ''` and fully-qualified names. That is a security fix
--     riding along with this change, not a cosmetic one.
--
-- (3) NEW OUTPUT COLUMNS REACH THE KOTLIN CLIENT.
--     get_user_secrets, search_user_secrets, get_user_secrets_with_shared and
--     get_secret_shares all gain columns. New columns are APPENDED so
--     positional readers keep working.
--
--     CORRECTED AFTER THE FACT. This block used to say supabase-kt decodes
--     with ignoreUnknownKeys = true by default, so an older Secret Manager
--     plugin ignores them - with "VERIFY that before deploying, because if a
--     client ever decoded strictly this would break reading every secret".
--
--     The warning was right and the reassurance was wrong. The plugins do not
--     read these RPCs; the HOST does, through SecretService, with raw kotlinx
--     rather than through supabase-kt - and raw kotlinx is strict. Deploying
--     this emptied the secret panels on every installed build at once, and
--     because the RPCs return LISTS it was all-or-nothing rather than a
--     missing field. search_user_secrets and get_secret_shares broke too and
--     went unreported, since a failed fetch renders as an empty list.
--
--     Fixed in BossConsole#144. Before extending an RPC's RETURNS TABLE, read
--     the "Decoding Supabase payloads" section of AGENTS.md: audit the client
--     by its decoder, not by which library you assume it uses.
--
-- Next migration: 20260802010000_secret_role_share_hierarchy.sql
-- ============================================================================


-- ============================================================================
-- SECTION 1: Schema
-- ============================================================================

ALTER TABLE "public"."secrets"
    ADD COLUMN IF NOT EXISTS "org_id" "uuid";

ALTER TABLE "public"."secrets" DROP CONSTRAINT IF EXISTS "secrets_org_id_fkey";
ALTER TABLE "public"."secrets"
    ADD CONSTRAINT "secrets_org_id_fkey" FOREIGN KEY ("org_id")
        REFERENCES "public"."organisations"("id") ON DELETE RESTRICT;

COMMENT ON COLUMN "public"."secrets"."org_id" IS
'NULL = a personal secret, with exactly the legacy semantics. Non-NULL = organisation-owned: every ACTIVE member may read it, organisation admins may edit and delete it. user_id remains the CREATOR in both cases. ON DELETE RESTRICT: an organisation holding secrets cannot be deleted out from under them.';

-- The old key (user_id, website, username) is wrong once a secret can be
-- organisation-owned: it lets two members create duplicate org rows for the same
-- site, while forbidding one person from having both a personal and an
-- organisation secret for that site. Replaced by two PARTIAL unique indexes.
--
-- Both still raise SQLSTATE 23505, so create_secret's and update_secret's
-- `EXCEPTION WHEN unique_violation` handlers keep producing the same message.
ALTER TABLE "public"."secrets" DROP CONSTRAINT IF EXISTS "unique_user_website_username";

CREATE UNIQUE INDEX IF NOT EXISTS "unique_personal_secret"
    ON "public"."secrets" ("user_id", "website", "username") WHERE "org_id" IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS "unique_org_secret"
    ON "public"."secrets" ("org_id", "website", "username") WHERE "org_id" IS NOT NULL;

CREATE INDEX IF NOT EXISTS "idx_secrets_org_id"
    ON "public"."secrets" ("org_id") WHERE "org_id" IS NOT NULL;


ALTER TABLE "public"."secret_shares"
    ADD COLUMN IF NOT EXISTS "shared_with_org_id" "uuid";

ALTER TABLE "public"."secret_shares" DROP CONSTRAINT IF EXISTS "secret_shares_shared_with_org_id_fkey";
ALTER TABLE "public"."secret_shares"
    ADD CONSTRAINT "secret_shares_shared_with_org_id_fkey" FOREIGN KEY ("shared_with_org_id")
        REFERENCES "public"."organisations"("id") ON DELETE CASCADE;

-- Exactly one of user / role / org. The previous two-way form is equivalent for
-- every existing row (shared_with_org_id is NULL everywhere), so the rewrite
-- validates clean. Note this is an ALTER TABLE ADD CONSTRAINT, which takes an
-- ACCESS EXCLUSIVE lock and scans the table -- trivial at current volume, but it
-- is a lock, so it belongs in a maintenance window on a large instance.
ALTER TABLE "public"."secret_shares" DROP CONSTRAINT IF EXISTS "share_target_check";
ALTER TABLE "public"."secret_shares" ADD CONSTRAINT "share_target_check" CHECK (
    (CASE WHEN "shared_with_user_id" IS NOT NULL THEN 1 ELSE 0 END
   + CASE WHEN "shared_with_role_id" IS NOT NULL THEN 1 ELSE 0 END
   + CASE WHEN "shared_with_org_id"  IS NOT NULL THEN 1 ELSE 0 END) = 1);

-- Matches the ON CONFLICT target share_secret needs for the organisation branch,
-- mirroring the existing unique_user_share / unique_role_share.
ALTER TABLE "public"."secret_shares" DROP CONSTRAINT IF EXISTS "unique_org_share";
ALTER TABLE "public"."secret_shares"
    ADD CONSTRAINT "unique_org_share" UNIQUE ("secret_id", "shared_with_org_id");

CREATE INDEX IF NOT EXISTS "idx_secret_shares_org_id"
    ON "public"."secret_shares" ("shared_with_org_id") WHERE "shared_with_org_id" IS NOT NULL;

COMMENT ON COLUMN "public"."secret_shares"."shared_with_org_id" IS
'Organisation-wide share target. Exactly one of shared_with_user_id / shared_with_role_id / shared_with_org_id is set, enforced by share_target_check.';


-- ============================================================================
-- SECTION 2: Access helpers -- ONE definition of "may I see / manage this"
-- ============================================================================
-- Five of the nine functions below need the same access question answered, and
-- the RLS policies need it too. Expressing it once is the only way the RPC path
-- and the direct-table path cannot drift apart.

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
             OR ss.shared_with_role_id IN (
                    SELECT ur.role_id FROM public.user_roles ur WHERE ur.user_id = auth.uid())
             OR (ss.shared_with_org_id IS NOT NULL AND public.is_org_member(ss.shared_with_org_id)) )
    );
$$;

ALTER FUNCTION "public"."can_access_secret"("uuid") OWNER TO "postgres";

COMMENT ON FUNCTION "public"."can_access_secret"("uuid") IS 'Whether the current user may READ a secret: owner, active member of the owning organisation, or the target of a live user / role / organisation share. The single definition used by both the retrieval RPCs and RLS.';


CREATE OR REPLACE FUNCTION "public"."can_manage_secret"("p_secret_id" "uuid")
RETURNS boolean
    LANGUAGE "sql" STABLE SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
    SELECT EXISTS (
        SELECT 1 FROM public.secrets s
        WHERE s.id = p_secret_id
          AND ( s.user_id = auth.uid()
             OR public.is_user_admin(auth.uid())
             OR (s.org_id IS NOT NULL AND public.is_org_admin(s.org_id)) )
    );
$$;

ALTER FUNCTION "public"."can_manage_secret"("uuid") OWNER TO "postgres";

COMMENT ON FUNCTION "public"."can_manage_secret"("uuid") IS 'Whether the current user may EDIT, DELETE or RE-SHARE a secret: its creator, a global admin, or an admin of the owning organisation. Being a plain member of the owning organisation grants read (can_access_secret) but never manage.';

-- REVOKE first: 20251023000014_grants.sql sets ALTER DEFAULT PRIVILEGES ... GRANT ALL ON
-- FUNCTIONS TO anon, so every function here is anon-executable the moment it is created
-- and a bare GRANT to authenticated does not take that away. Same trap as ON TABLES,
-- caught there and missed here. None of these were an authorization hole - they all
-- resolve through resolve_org_actor, which returns NULL for anon - but an
-- unauthenticated DB-reachable surface nobody intended is worth closing.
REVOKE EXECUTE ON FUNCTION "public"."can_access_secret"("uuid") FROM PUBLIC, "anon";
GRANT EXECUTE ON FUNCTION "public"."can_access_secret"("uuid") TO "authenticated", "service_role";
REVOKE EXECUTE ON FUNCTION "public"."can_manage_secret"("uuid") FROM PUBLIC, "anon";
GRANT EXECUTE ON FUNCTION "public"."can_manage_secret"("uuid") TO "authenticated", "service_role";


-- ============================================================================
-- SECTION 3: Drop the functions whose signature or return type changes
-- ============================================================================
-- delete_secret is deliberately absent: only its BODY changes, so a plain
-- CREATE OR REPLACE is correct and its grants are untouched.

DROP FUNCTION IF EXISTS "public"."create_secret"("text","text","text","text",timestamp with time zone,"text"[],boolean,"text","text"[]);
DROP FUNCTION IF EXISTS "public"."update_secret"("uuid","text","text","text","text",timestamp with time zone,"text"[],boolean,"text","text"[]);
DROP FUNCTION IF EXISTS "public"."get_user_secrets"(integer,integer);
DROP FUNCTION IF EXISTS "public"."search_user_secrets"("text",integer,integer);
DROP FUNCTION IF EXISTS "public"."get_user_secrets_with_shared"(integer,integer);
DROP FUNCTION IF EXISTS "public"."share_secret"("uuid","uuid","uuid","text",timestamp with time zone);
DROP FUNCTION IF EXISTS "public"."unshare_secret"("uuid","uuid","uuid");
DROP FUNCTION IF EXISTS "public"."get_secret_shares"("uuid");


-- ============================================================================
-- SECTION 4: Mutation functions
-- ============================================================================

CREATE OR REPLACE FUNCTION "public"."create_secret"(
    "p_website" "text",
    "p_username" "text",
    "p_password" "text",
    "p_notes" "text" DEFAULT NULL::"text",
    "p_expiration_date" timestamp with time zone DEFAULT NULL::timestamp with time zone,
    "p_tags" "text"[] DEFAULT NULL::"text"[],
    "p_twofa_enabled" boolean DEFAULT false,
    "p_twofa_type" "text" DEFAULT NULL::"text",
    "p_recovery_codes" "text"[] DEFAULT NULL::"text"[],
    "p_org_id" "uuid" DEFAULT NULL::"uuid"
) RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
DECLARE
    v_secret_id UUID;
    v_encrypted_password TEXT;
    v_encrypted_codes TEXT;
BEGIN
    IF auth.uid() IS NULL THEN
        RETURN jsonb_build_object('success', false, 'error', 'Not authenticated');
    END IF;

    -- Only an active member may create a secret owned by an organisation.
    IF p_org_id IS NOT NULL AND NOT public.is_org_member(p_org_id) THEN
        RETURN jsonb_build_object('success', false, 'error',
            'You are not a member of that organisation');
    END IF;

    v_encrypted_password := public.encrypt_text(p_password);

    -- user_id stays auth.uid() even for an organisation secret: it records who
    -- CREATED it, which the audit trail needs. Ownership is org_id.
    INSERT INTO public.secrets (
        user_id, org_id, website, username, password_encrypted, notes, expiration_date
    )
    VALUES (
        auth.uid(), p_org_id, p_website, p_username, v_encrypted_password, p_notes, p_expiration_date
    )
    RETURNING id INTO v_secret_id;

    IF p_twofa_enabled THEN
        IF p_recovery_codes IS NOT NULL AND array_length(p_recovery_codes, 1) > 0 THEN
            v_encrypted_codes := public.encrypt_text(array_to_json(p_recovery_codes)::text);
        END IF;

        INSERT INTO public.secret_metadata (secret_id, twofa_enabled, twofa_type, recovery_codes_encrypted)
        VALUES (v_secret_id, p_twofa_enabled, p_twofa_type, v_encrypted_codes);
    END IF;

    IF p_tags IS NOT NULL AND array_length(p_tags, 1) > 0 THEN
        INSERT INTO public.secret_tags (secret_id, tag)
        SELECT v_secret_id, unnest(p_tags);
    END IF;

    RETURN jsonb_build_object('success', true, 'secret_id', v_secret_id, 'message', 'Secret created successfully');
EXCEPTION
    WHEN unique_violation THEN
        -- Raised by unique_personal_secret or unique_org_secret; both are 23505,
        -- so this message is unchanged from the pre-organisation behaviour.
        RETURN jsonb_build_object('success', false, 'error', 'A secret for this website and username already exists');
    WHEN OTHERS THEN
        RETURN jsonb_build_object('success', false, 'error', SQLERRM);
END;
$$;

ALTER FUNCTION "public"."create_secret"("text","text","text","text",timestamp with time zone,"text"[],boolean,"text","text"[],"uuid") OWNER TO "postgres";

COMMENT ON FUNCTION "public"."create_secret"("text","text","text","text",timestamp with time zone,"text"[],boolean,"text","text"[],"uuid") IS 'Creates a secret. p_org_id NULL = personal (legacy behaviour); non-NULL = organisation-owned, which requires active membership. user_id always records the creator.';


CREATE OR REPLACE FUNCTION "public"."update_secret"(
    "p_secret_id" "uuid",
    "p_website" "text",
    "p_username" "text",
    "p_password" "text",
    "p_notes" "text" DEFAULT NULL::"text",
    "p_expiration_date" timestamp with time zone DEFAULT NULL::timestamp with time zone,
    "p_tags" "text"[] DEFAULT NULL::"text"[],
    "p_twofa_enabled" boolean DEFAULT false,
    "p_twofa_type" "text" DEFAULT NULL::"text",
    "p_recovery_codes" "text"[] DEFAULT NULL::"text"[],
    "p_org_id" "uuid" DEFAULT NULL::"uuid",
    "p_change_owner_org" boolean DEFAULT false
) RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
DECLARE
    v_encrypted_password TEXT;
    v_encrypted_codes TEXT;
    v_current_org UUID;
BEGIN
    IF auth.uid() IS NULL THEN
        RETURN jsonb_build_object('success', false, 'error', 'Not authenticated');
    END IF;

    -- Was: WHERE id = ... AND user_id = auth.uid(). Now the creator, a global
    -- admin, or an admin of the owning organisation may edit.
    IF NOT public.can_manage_secret(p_secret_id) THEN
        RETURN jsonb_build_object('success', false, 'error', 'Secret not found or access denied');
    END IF;

    SELECT s.org_id INTO v_current_org FROM public.secrets s WHERE s.id = p_secret_id;

    -- Moving a secret between personal and organisation ownership needs an
    -- explicit flag. Without it, an old client that does not send p_org_id would
    -- silently un-own every organisation secret it saved -- COALESCE cannot
    -- distinguish "leave alone" from "set to NULL".
    IF p_change_owner_org THEN
        IF p_org_id IS NOT NULL AND NOT public.is_org_member(p_org_id) THEN
            RETURN jsonb_build_object('success', false, 'error',
                'You are not a member of that organisation');
        END IF;
        -- Moving OUT of an organisation, or between organisations, is an admin act
        -- on the organisation losing the secret.
        IF v_current_org IS NOT NULL
           AND COALESCE(p_org_id, '00000000-0000-0000-0000-000000000000'::uuid) <> v_current_org
           AND NOT public.is_org_admin(v_current_org) THEN
            RETURN jsonb_build_object('success', false, 'error',
                'Only an administrator of the owning organisation can move this secret');
        END IF;
    END IF;

    v_encrypted_password := public.encrypt_text(p_password);

    UPDATE public.secrets
    SET website = p_website,
        username = p_username,
        password_encrypted = v_encrypted_password,
        notes = p_notes,
        expiration_date = p_expiration_date,
        org_id = CASE WHEN p_change_owner_org THEN p_org_id ELSE org_id END,
        updated_at = now()
    WHERE id = p_secret_id;

    IF p_twofa_enabled THEN
        IF p_recovery_codes IS NOT NULL AND array_length(p_recovery_codes, 1) > 0 THEN
            v_encrypted_codes := public.encrypt_text(array_to_json(p_recovery_codes)::text);
        END IF;

        INSERT INTO public.secret_metadata (secret_id, twofa_enabled, twofa_type, recovery_codes_encrypted)
        VALUES (p_secret_id, p_twofa_enabled, p_twofa_type, v_encrypted_codes)
        ON CONFLICT (secret_id) DO UPDATE
        SET twofa_enabled = p_twofa_enabled, twofa_type = p_twofa_type,
            recovery_codes_encrypted = v_encrypted_codes, updated_at = now();
    ELSE
        DELETE FROM public.secret_metadata WHERE secret_id = p_secret_id;
    END IF;

    DELETE FROM public.secret_tags WHERE secret_id = p_secret_id;
    IF p_tags IS NOT NULL AND array_length(p_tags, 1) > 0 THEN
        INSERT INTO public.secret_tags (secret_id, tag)
        SELECT p_secret_id, unnest(p_tags);
    END IF;

    RETURN jsonb_build_object('success', true, 'message', 'Secret updated successfully');
EXCEPTION
    WHEN unique_violation THEN
        RETURN jsonb_build_object('success', false, 'error', 'A secret for this website and username already exists');
    WHEN OTHERS THEN
        RETURN jsonb_build_object('success', false, 'error', SQLERRM);
END;
$$;

ALTER FUNCTION "public"."update_secret"("uuid","text","text","text","text",timestamp with time zone,"text"[],boolean,"text","text"[],"uuid",boolean) OWNER TO "postgres";

COMMENT ON FUNCTION "public"."update_secret"("uuid","text","text","text","text",timestamp with time zone,"text"[],boolean,"text","text"[],"uuid",boolean) IS 'Updates a secret. Authorization widened from owner-only to can_manage_secret (creator, global admin, or owning-organisation admin). p_org_id is applied ONLY when p_change_owner_org is true, so a client that does not send it cannot silently un-own an organisation secret.';


-- Body-only change, so CREATE OR REPLACE (no DROP) -- the signature and its
-- grants are untouched.
CREATE OR REPLACE FUNCTION "public"."delete_secret"("p_secret_id" "uuid") RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
BEGIN
    IF auth.uid() IS NULL THEN
        RETURN jsonb_build_object('success', false, 'error', 'Not authenticated');
    END IF;

    IF NOT public.can_manage_secret(p_secret_id) THEN
        RETURN jsonb_build_object('success', false, 'error', 'Secret not found or access denied');
    END IF;

    -- Cascades to secret_metadata, secret_tags, secret_shares, secret_access_log.
    DELETE FROM public.secrets WHERE id = p_secret_id;

    IF NOT FOUND THEN
        RETURN jsonb_build_object('success', false, 'error', 'Secret not found or access denied');
    END IF;

    RETURN jsonb_build_object('success', true, 'message', 'Secret deleted successfully');
EXCEPTION
    WHEN OTHERS THEN
        RETURN jsonb_build_object('success', false, 'error', SQLERRM);
END;
$$;

ALTER FUNCTION "public"."delete_secret"("uuid") OWNER TO "postgres";


-- ============================================================================
-- SECTION 5: Retrieval functions
-- ============================================================================
-- New columns are APPENDED (org_id, org_slug, is_org_owned, can_manage) so a
-- positional reader keeps working.

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
    ORDER BY s.created_at DESC
    LIMIT p_limit OFFSET p_offset;
END;
$$;

ALTER FUNCTION "public"."get_user_secrets"(integer,integer) OWNER TO "postgres";

COMMENT ON FUNCTION "public"."get_user_secrets"(integer,integer) IS 'The caller''s own secrets plus the secrets of organisations they actively belong to. can_manage is resolved server-side per row -- the client must never infer edit rights.';


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
    ORDER BY s.created_at DESC
    LIMIT p_limit OFFSET p_offset;
END;
$$;

ALTER FUNCTION "public"."search_user_secrets"("text",integer,integer) OWNER TO "postgres";


-- get_user_secrets_with_shared: the three original UNION sources plus two.
--
-- The `priority` column is new and load-bearing. The original deduped with
-- ORDER BY a.id, a.is_owner DESC, which is ambiguous once a secret can reach a
-- caller by more than one non-owner route (an organisation they belong to AND a
-- role share, say) -- Postgres would pick arbitrarily, so access_level and
-- shared_by_email could flap between calls for the same row. priority makes the
-- precedence explicit and stable.
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

        -- Source 4 (NEW): secrets OWNED BY an organisation the caller belongs to.
        -- is_owner reflects creatorship, so a member sees a colleague's org secret
        -- with is_owner = false while still being able to read it.
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

        -- Source 3: shared with a role the caller holds.
        SELECT s.id, FALSE, u.email, ss.access_level, NULL::TEXT, 4
        FROM public.secrets s
        JOIN public.secret_shares ss ON ss.secret_id = s.id
        JOIN auth.users u ON u.id = ss.shared_by
        WHERE ss.shared_with_role_id IN (
                  SELECT ur.role_id FROM public.user_roles ur WHERE ur.user_id = auth.uid())
          AND (ss.expires_at IS NULL OR ss.expires_at > now())

        UNION ALL

        -- Source 5 (NEW): shared with an organisation the caller belongs to.
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
        -- Ownership first, then the explicit precedence above.
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

COMMENT ON FUNCTION "public"."get_user_secrets_with_shared"(integer,integer) IS 'Everything the caller can read: own secrets, organisation-owned secrets of organisations they belong to, and secrets shared with them by user, role or organisation. Deduplicated with an explicit priority so access_level cannot flap when a secret is reachable by several routes.';


-- ============================================================================
-- SECTION 6: Sharing functions
-- ============================================================================

CREATE OR REPLACE FUNCTION "public"."share_secret"(
    "p_secret_id" "uuid",
    "p_target_user_id" "uuid" DEFAULT NULL::"uuid",
    "p_target_role_id" "uuid" DEFAULT NULL::"uuid",
    "p_notes" "text" DEFAULT NULL::"text",
    "p_expires_at" timestamp with time zone DEFAULT NULL::timestamp with time zone,
    "p_target_org_id" "uuid" DEFAULT NULL::"uuid"
) RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
DECLARE
    v_is_owner BOOLEAN;
    v_is_org_admin BOOLEAN;
    v_target_email TEXT;
    v_target_role_name TEXT;
    v_target_org_slug TEXT;
    v_secret_website TEXT;
    v_secret_org UUID;
    v_target_count INTEGER;
    v_via TEXT;
BEGIN
    IF auth.uid() IS NULL THEN
        RETURN jsonb_build_object('success', false, 'error', 'Not authenticated');
    END IF;

    -- Was an inline "owner OR literal admin role" check. can_manage_secret keeps
    -- both of those and additionally lets an admin of the OWNING organisation
    -- share an organisation secret -- which is the point of org ownership.
    IF NOT public.can_manage_secret(p_secret_id) THEN
        RETURN jsonb_build_object('success', false, 'error',
            'Unauthorized: You must be the owner, an organisation administrator, or an admin to share this secret');
    END IF;

    SELECT s.website, s.user_id = auth.uid(), s.org_id
      INTO v_secret_website, v_is_owner, v_secret_org
      FROM public.secrets s WHERE s.id = p_secret_id;

    IF v_secret_website IS NULL AND NOT FOUND THEN
        RETURN jsonb_build_object('success', false, 'error', 'Secret not found');
    END IF;

    v_is_org_admin := v_secret_org IS NOT NULL AND public.is_org_admin(v_secret_org);

    -- Exactly one of three targets. share_target_check would catch this at insert
    -- time, but a clear message beats a constraint violation.
    v_target_count := (CASE WHEN p_target_user_id IS NOT NULL THEN 1 ELSE 0 END)
                    + (CASE WHEN p_target_role_id IS NOT NULL THEN 1 ELSE 0 END)
                    + (CASE WHEN p_target_org_id  IS NOT NULL THEN 1 ELSE 0 END);
    IF v_target_count <> 1 THEN
        RETURN jsonb_build_object('success', false, 'error',
            'Must specify exactly one of target_user_id, target_role_id or target_org_id');
    END IF;

    IF p_target_user_id IS NOT NULL THEN
        SELECT u.email INTO v_target_email FROM auth.users u WHERE u.id = p_target_user_id;
        IF v_target_email IS NULL THEN
            RETURN jsonb_build_object('success', false, 'error', 'User not found');
        END IF;

        INSERT INTO public.secret_shares (
            secret_id, shared_with_user_id, shared_with_role_id, shared_with_org_id,
            shared_by, access_level, expires_at, notes
        ) VALUES (
            p_secret_id, p_target_user_id, NULL, NULL,
            auth.uid(), 'read', p_expires_at, p_notes
        )
        ON CONFLICT (secret_id, shared_with_user_id)
        DO UPDATE SET expires_at = EXCLUDED.expires_at, notes = EXCLUDED.notes, created_at = now();
    END IF;

    IF p_target_role_id IS NOT NULL THEN
        SELECT r.name INTO v_target_role_name FROM public.roles r WHERE r.id = p_target_role_id;
        IF v_target_role_name IS NULL THEN
            RETURN jsonb_build_object('success', false, 'error', 'Role not found');
        END IF;

        INSERT INTO public.secret_shares (
            secret_id, shared_with_user_id, shared_with_role_id, shared_with_org_id,
            shared_by, access_level, expires_at, notes
        ) VALUES (
            p_secret_id, NULL, p_target_role_id, NULL,
            auth.uid(), 'read', p_expires_at, p_notes
        )
        ON CONFLICT (secret_id, shared_with_role_id)
        DO UPDATE SET expires_at = EXCLUDED.expires_at, notes = EXCLUDED.notes, created_at = now();
    END IF;

    IF p_target_org_id IS NOT NULL THEN
        SELECT o.slug INTO v_target_org_slug
        FROM public.organisations o WHERE o.id = p_target_org_id;
        IF v_target_org_slug IS NULL THEN
            RETURN jsonb_build_object('success', false, 'error', 'Organisation not found');
        END IF;

        -- You may only share INTO an organisation you belong to. Otherwise any
        -- user could push a credential at an arbitrary organisation's members --
        -- a phishing primitive, since the Secret Manager would then display it
        -- to them as a legitimately shared secret.
        IF NOT public.is_org_member(p_target_org_id) THEN
            RETURN jsonb_build_object('success', false, 'error',
                'You can only share with an organisation you belong to');
        END IF;

        INSERT INTO public.secret_shares (
            secret_id, shared_with_user_id, shared_with_role_id, shared_with_org_id,
            shared_by, access_level, expires_at, notes
        ) VALUES (
            p_secret_id, NULL, NULL, p_target_org_id,
            auth.uid(), 'read', p_expires_at, p_notes
        )
        ON CONFLICT (secret_id, shared_with_org_id)
        DO UPDATE SET expires_at = EXCLUDED.expires_at, notes = EXCLUDED.notes, created_at = now();
    END IF;

    v_via := CASE WHEN v_is_owner THEN 'owner'
                  WHEN v_is_org_admin THEN 'org_admin'
                  ELSE 'admin_override' END;

    INSERT INTO public.secret_access_log (
        secret_id, user_id, operation, access_granted_via, metadata
    ) VALUES (
        p_secret_id, auth.uid(), 'share', v_via,
        jsonb_build_object(
            'target_user_id', p_target_user_id,
            'target_role_id', p_target_role_id,
            'target_org_id', p_target_org_id,
            'target_email', v_target_email,
            'target_role_name', v_target_role_name,
            'target_org_slug', v_target_org_slug,
            'secret_website', v_secret_website,
            'expires_at', p_expires_at,
            'notes', p_notes
        )
    );

    RETURN jsonb_build_object(
        'success', true,
        'message', 'Secret shared successfully',
        'target_email', v_target_email,
        'target_role', v_target_role_name,
        'target_org', v_target_org_slug
    );
END;
$$;

ALTER FUNCTION "public"."share_secret"("uuid","uuid","uuid","text",timestamp with time zone,"uuid") OWNER TO "postgres";

COMMENT ON FUNCTION "public"."share_secret"("uuid","uuid","uuid","text",timestamp with time zone,"uuid") IS 'Shares a secret with exactly one of a user, a role, or an organisation. Authorization is can_manage_secret, so organisation admins can share organisation-owned secrets. Sharing INTO an organisation requires membership of it, which prevents pushing a credential at strangers. access_granted_via gains org_admin.';


CREATE OR REPLACE FUNCTION "public"."unshare_secret"(
    "p_secret_id" "uuid",
    "p_target_user_id" "uuid" DEFAULT NULL::"uuid",
    "p_target_role_id" "uuid" DEFAULT NULL::"uuid",
    "p_target_org_id" "uuid" DEFAULT NULL::"uuid"
) RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
DECLARE
    v_is_owner BOOLEAN;
    v_secret_org UUID;
    v_deleted_count INTEGER;
    v_via TEXT;
BEGIN
    IF auth.uid() IS NULL THEN
        RETURN jsonb_build_object('success', false, 'error', 'Not authenticated');
    END IF;

    IF NOT public.can_manage_secret(p_secret_id) THEN
        RETURN jsonb_build_object('success', false, 'error',
            'Unauthorized: You must be the owner, an organisation administrator, or an admin to revoke access');
    END IF;

    SELECT s.user_id = auth.uid(), s.org_id INTO v_is_owner, v_secret_org
    FROM public.secrets s WHERE s.id = p_secret_id;

    DELETE FROM public.secret_shares
    WHERE secret_id = p_secret_id
      AND (
          (p_target_user_id IS NOT NULL AND shared_with_user_id = p_target_user_id)
       OR (p_target_role_id IS NOT NULL AND shared_with_role_id = p_target_role_id)
       OR (p_target_org_id  IS NOT NULL AND shared_with_org_id  = p_target_org_id)
      );

    GET DIAGNOSTICS v_deleted_count = ROW_COUNT;

    IF v_deleted_count = 0 THEN
        RETURN jsonb_build_object('success', false, 'error', 'No matching share found to revoke');
    END IF;

    v_via := CASE WHEN v_is_owner THEN 'owner'
                  WHEN v_secret_org IS NOT NULL AND public.is_org_admin(v_secret_org) THEN 'org_admin'
                  ELSE 'admin_override' END;

    INSERT INTO public.secret_access_log (
        secret_id, user_id, operation, access_granted_via, metadata
    ) VALUES (
        p_secret_id, auth.uid(), 'unshare', v_via,
        jsonb_build_object(
            'target_user_id', p_target_user_id,
            'target_role_id', p_target_role_id,
            'target_org_id', p_target_org_id
        )
    );

    RETURN jsonb_build_object('success', true, 'message', 'Access revoked successfully',
        'revoked_count', v_deleted_count);
END;
$$;

ALTER FUNCTION "public"."unshare_secret"("uuid","uuid","uuid","uuid") OWNER TO "postgres";


CREATE OR REPLACE FUNCTION "public"."get_secret_shares"("p_secret_id" "uuid")
RETURNS TABLE(
    "share_id" "uuid",
    "shared_with_user_id" "uuid", "shared_with_user_email" "text",
    "shared_with_role_id" "uuid", "shared_with_role_name" "text",
    "access_level" "text", "shared_by_email" "text",
    "created_at" timestamp with time zone, "expires_at" timestamp with time zone,
    "notes" "text",
    "shared_with_org_id" "uuid", "shared_with_org_slug" "text"
)
    LANGUAGE "plpgsql" STABLE SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
BEGIN
    IF auth.uid() IS NULL THEN
        RAISE EXCEPTION 'Not authenticated';
    END IF;

    IF NOT public.can_manage_secret(p_secret_id) THEN
        RAISE EXCEPTION 'Unauthorized: You must be the owner, an organisation administrator, or an admin to view shares';
    END IF;

    RETURN QUERY
    SELECT
        ss.id AS share_id,
        ss.shared_with_user_id,
        u.email::text AS shared_with_user_email,
        ss.shared_with_role_id,
        r.name::text AS shared_with_role_name,
        ss.access_level,
        sb.email::text AS shared_by_email,
        ss.created_at,
        ss.expires_at,
        ss.notes,
        ss.shared_with_org_id,
        o.slug::text AS shared_with_org_slug
    FROM public.secret_shares ss
    LEFT JOIN auth.users u ON u.id = ss.shared_with_user_id
    LEFT JOIN public.roles r ON r.id = ss.shared_with_role_id
    LEFT JOIN public.organisations o ON o.id = ss.shared_with_org_id
    LEFT JOIN auth.users sb ON sb.id = ss.shared_by
    WHERE ss.secret_id = p_secret_id
    ORDER BY ss.created_at DESC;
END;
$$;

ALTER FUNCTION "public"."get_secret_shares"("uuid") OWNER TO "postgres";


-- ============================================================================
-- SECTION 7: Re-GRANT everything that was dropped
-- ============================================================================
-- 20251023000014_grants.sql granted these to anon, authenticated AND service_role.
-- The anon grant is DELIBERATELY NOT restored: every one of these functions
-- resolves its subject from auth.uid(), which is NULL for anon, so an anonymous
-- call could only ever fail (create_secret would violate secrets.user_id NOT
-- NULL). Removing a grant that could not be used narrows the surface at no cost.
-- Recorded here explicitly so the difference from the original grants file is
-- intentional rather than an omission.

REVOKE EXECUTE ON FUNCTION "public"."create_secret"("text","text","text","text",timestamp with time zone,"text"[],boolean,"text","text"[],"uuid") FROM PUBLIC, "anon";
GRANT EXECUTE ON FUNCTION "public"."create_secret"("text","text","text","text",timestamp with time zone,"text"[],boolean,"text","text"[],"uuid")                      TO "authenticated", "service_role";
REVOKE EXECUTE ON FUNCTION "public"."update_secret"("uuid","text","text","text","text",timestamp with time zone,"text"[],boolean,"text","text"[],"uuid",boolean) FROM PUBLIC, "anon";
GRANT EXECUTE ON FUNCTION "public"."update_secret"("uuid","text","text","text","text",timestamp with time zone,"text"[],boolean,"text","text"[],"uuid",boolean)      TO "authenticated", "service_role";
REVOKE EXECUTE ON FUNCTION "public"."delete_secret"("uuid") FROM PUBLIC, "anon";
GRANT EXECUTE ON FUNCTION "public"."delete_secret"("uuid")                                                                                                          TO "authenticated", "service_role";
REVOKE EXECUTE ON FUNCTION "public"."get_user_secrets"(integer,integer) FROM PUBLIC, "anon";
GRANT EXECUTE ON FUNCTION "public"."get_user_secrets"(integer,integer)                                                                                              TO "authenticated", "service_role";
REVOKE EXECUTE ON FUNCTION "public"."search_user_secrets"("text",integer,integer) FROM PUBLIC, "anon";
GRANT EXECUTE ON FUNCTION "public"."search_user_secrets"("text",integer,integer)                                                                                    TO "authenticated", "service_role";
REVOKE EXECUTE ON FUNCTION "public"."get_user_secrets_with_shared"(integer,integer) FROM PUBLIC, "anon";
GRANT EXECUTE ON FUNCTION "public"."get_user_secrets_with_shared"(integer,integer)                                                                                  TO "authenticated", "service_role";
REVOKE EXECUTE ON FUNCTION "public"."share_secret"("uuid","uuid","uuid","text",timestamp with time zone,"uuid") FROM PUBLIC, "anon";
GRANT EXECUTE ON FUNCTION "public"."share_secret"("uuid","uuid","uuid","text",timestamp with time zone,"uuid")                                                       TO "authenticated", "service_role";
REVOKE EXECUTE ON FUNCTION "public"."unshare_secret"("uuid","uuid","uuid","uuid") FROM PUBLIC, "anon";
GRANT EXECUTE ON FUNCTION "public"."unshare_secret"("uuid","uuid","uuid","uuid")                                                                                    TO "authenticated", "service_role";
REVOKE EXECUTE ON FUNCTION "public"."get_secret_shares"("uuid") FROM PUBLIC, "anon";
GRANT EXECUTE ON FUNCTION "public"."get_secret_shares"("uuid")                                                                                                      TO "authenticated", "service_role";


-- ============================================================================
-- SECTION 8: RLS
-- ============================================================================
-- The RPCs above are SECURITY DEFINER and bypass RLS, so these policies govern
-- DIRECT PostgREST access to the tables. They must agree with the RPCs or the two
-- paths diverge -- which is why both are written in terms of the same
-- is_org_member / is_org_admin predicates.

-- secrets ---------------------------------------------------------------------
DROP POLICY IF EXISTS "Users can view own secrets"   ON "public"."secrets";
DROP POLICY IF EXISTS "Users can view own or organisation secrets" ON "public"."secrets";
CREATE POLICY "Users can view own or organisation secrets" ON "public"."secrets"
    FOR SELECT USING (
        "auth"."uid"() = "user_id"
        OR ("org_id" IS NOT NULL AND "public"."is_org_member"("org_id"))
    );

DROP POLICY IF EXISTS "Users can create own secrets" ON "public"."secrets";
DROP POLICY IF EXISTS "Users can create own or organisation secrets" ON "public"."secrets";
CREATE POLICY "Users can create own or organisation secrets" ON "public"."secrets"
    FOR INSERT WITH CHECK (
        "auth"."uid"() = "user_id"
        AND ("org_id" IS NULL OR "public"."is_org_member"("org_id"))
    );

DROP POLICY IF EXISTS "Users can update own secrets" ON "public"."secrets";
DROP POLICY IF EXISTS "Owners and organisation admins can update secrets" ON "public"."secrets";
CREATE POLICY "Owners and organisation admins can update secrets" ON "public"."secrets"
    FOR UPDATE USING (
        "auth"."uid"() = "user_id"
        OR ("org_id" IS NOT NULL AND "public"."is_org_admin"("org_id"))
    ) WITH CHECK (
        "auth"."uid"() = "user_id"
        OR ("org_id" IS NOT NULL AND "public"."is_org_admin"("org_id"))
    );

DROP POLICY IF EXISTS "Users can delete own secrets" ON "public"."secrets";
DROP POLICY IF EXISTS "Owners and organisation admins can delete secrets" ON "public"."secrets";
CREATE POLICY "Owners and organisation admins can delete secrets" ON "public"."secrets"
    FOR DELETE USING (
        "auth"."uid"() = "user_id"
        OR ("org_id" IS NOT NULL AND "public"."is_org_admin"("org_id"))
    );

-- secret_metadata -------------------------------------------------------------
-- Reads follow the parent secret's read rule; writes follow its manage rule. The
-- original used the owner test for all four.
DROP POLICY IF EXISTS "Users can view own secret metadata" ON "public"."secret_metadata";
CREATE POLICY "Users can view own secret metadata" ON "public"."secret_metadata"
    FOR SELECT USING (EXISTS (
        SELECT 1 FROM "public"."secrets" s
        WHERE s."id" = "secret_metadata"."secret_id"
          AND (s."user_id" = "auth"."uid"()
               OR (s."org_id" IS NOT NULL AND "public"."is_org_member"(s."org_id")))
    ));

DROP POLICY IF EXISTS "Users can create own secret metadata" ON "public"."secret_metadata";
CREATE POLICY "Users can create own secret metadata" ON "public"."secret_metadata"
    FOR INSERT WITH CHECK (EXISTS (
        SELECT 1 FROM "public"."secrets" s
        WHERE s."id" = "secret_metadata"."secret_id"
          AND (s."user_id" = "auth"."uid"()
               OR (s."org_id" IS NOT NULL AND "public"."is_org_admin"(s."org_id")))
    ));

DROP POLICY IF EXISTS "Users can update own secret metadata" ON "public"."secret_metadata";
CREATE POLICY "Users can update own secret metadata" ON "public"."secret_metadata"
    FOR UPDATE USING (EXISTS (
        SELECT 1 FROM "public"."secrets" s
        WHERE s."id" = "secret_metadata"."secret_id"
          AND (s."user_id" = "auth"."uid"()
               OR (s."org_id" IS NOT NULL AND "public"."is_org_admin"(s."org_id")))
    ));

DROP POLICY IF EXISTS "Users can delete own secret metadata" ON "public"."secret_metadata";
CREATE POLICY "Users can delete own secret metadata" ON "public"."secret_metadata"
    FOR DELETE USING (EXISTS (
        SELECT 1 FROM "public"."secrets" s
        WHERE s."id" = "secret_metadata"."secret_id"
          AND (s."user_id" = "auth"."uid"()
               OR (s."org_id" IS NOT NULL AND "public"."is_org_admin"(s."org_id")))
    ));

-- secret_tags -----------------------------------------------------------------
DROP POLICY IF EXISTS "Users can view own secret tags" ON "public"."secret_tags";
CREATE POLICY "Users can view own secret tags" ON "public"."secret_tags"
    FOR SELECT USING (EXISTS (
        SELECT 1 FROM "public"."secrets" s
        WHERE s."id" = "secret_tags"."secret_id"
          AND (s."user_id" = "auth"."uid"()
               OR (s."org_id" IS NOT NULL AND "public"."is_org_member"(s."org_id")))
    ));

DROP POLICY IF EXISTS "Users can create own secret tags" ON "public"."secret_tags";
CREATE POLICY "Users can create own secret tags" ON "public"."secret_tags"
    FOR INSERT WITH CHECK (EXISTS (
        SELECT 1 FROM "public"."secrets" s
        WHERE s."id" = "secret_tags"."secret_id"
          AND (s."user_id" = "auth"."uid"()
               OR (s."org_id" IS NOT NULL AND "public"."is_org_admin"(s."org_id")))
    ));

DROP POLICY IF EXISTS "Users can delete own secret tags" ON "public"."secret_tags";
CREATE POLICY "Users can delete own secret tags" ON "public"."secret_tags"
    FOR DELETE USING (EXISTS (
        SELECT 1 FROM "public"."secrets" s
        WHERE s."id" = "secret_tags"."secret_id"
          AND (s."user_id" = "auth"."uid"()
               OR (s."org_id" IS NOT NULL AND "public"."is_org_admin"(s."org_id")))
    ));

-- secret_shares ---------------------------------------------------------------
DROP POLICY IF EXISTS "secret_shares_select" ON "public"."secret_shares";
CREATE POLICY "secret_shares_select" ON "public"."secret_shares"
    FOR SELECT USING (
        "public"."can_manage_secret"("secret_id")
        OR "shared_with_user_id" = "auth"."uid"()
        OR "shared_with_role_id" IN (
            SELECT ur."role_id" FROM "public"."user_roles" ur WHERE ur."user_id" = "auth"."uid"())
        OR ("shared_with_org_id" IS NOT NULL AND "public"."is_org_member"("shared_with_org_id"))
    );

DROP POLICY IF EXISTS "secret_shares_insert" ON "public"."secret_shares";
CREATE POLICY "secret_shares_insert" ON "public"."secret_shares"
    FOR INSERT WITH CHECK ("public"."can_manage_secret"("secret_id"));

DROP POLICY IF EXISTS "secret_shares_update" ON "public"."secret_shares";
CREATE POLICY "secret_shares_update" ON "public"."secret_shares"
    FOR UPDATE USING ("public"."can_manage_secret"("secret_id"))
             WITH CHECK ("public"."can_manage_secret"("secret_id"));

DROP POLICY IF EXISTS "secret_shares_delete" ON "public"."secret_shares";
CREATE POLICY "secret_shares_delete" ON "public"."secret_shares"
    FOR DELETE USING ("public"."can_manage_secret"("secret_id"));


-- ============================================================================
-- SECTION 9: Access-review notes
-- ============================================================================
-- Things that are now permitted that were not before. Each is intended; they are
-- listed so a reviewer can confirm rather than discover.
--
-- 1. An ACTIVE MEMBER of an organisation can READ every secret owned by that
--    organisation (and the password is decrypted server-side, as it already was
--    for personal secrets). Membership is the access grant -- that is the feature.
-- 2. An ADMIN of an organisation can EDIT, DELETE and RE-SHARE any secret owned
--    by it, including ones a colleague created.
-- 3. share_secret / unshare_secret / get_secret_shares moved from
--    "owner OR literal admin role" to can_manage_secret. Global admins keep
--    access via is_user_admin inside the helper; organisation admins GAIN it for
--    organisation-owned secrets.
-- 4. Sharing INTO an organisation requires membership of that organisation, so
--    the new target cannot be used to push a credential at strangers.
-- 5. Removing someone from an organisation revokes all of this immediately --
--    remove_organisation_member deletes both the membership row and the user's
--    organisation role rows, and every predicate here reads those tables live
--    rather than trusting a JWT claim.
--
-- Unchanged: the encryption model. There is still ONE global master key in
-- Supabase Vault (20251023000005), so org ownership is an ACCESS-CONTROL boundary,
-- not a cryptographic one. Anything able to call decrypt_text can read any
-- secret. Per-organisation keys would be a separate piece of work.


-- ============================================================================
-- End of File: 20260802000000_secrets_org_ownership.sql
-- ============================================================================
-- Next Migration: 20260802010000_secret_role_share_hierarchy.sql
-- ============================================================================
