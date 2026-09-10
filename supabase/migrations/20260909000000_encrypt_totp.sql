-- ============================================================================
-- BOSS Database Schema: Encrypt Plaintext TOTP Secrets
-- ============================================================================
-- File: 20260909000000_encrypt_totp.sql
-- Description: Secures twofa_secret by storing it encrypted at rest using a
--              'v1:' prefix to guarantee idempotence.
--              Implements safe decryption and auto-encrypt triggers.
-- Operational scope: UPDATE replaces visible rows, not old physical bytes.
-- Dead tuples, WAL and existing backups/PITR may retain plaintext. This is not
-- secure erasure; backup retention and physical storage reclamation are separate
-- operator decisions. Logical reads after commit see encrypted storage.
-- Data-only COPY restores into a schema with this trigger enabled reject v1:
-- envelopes. A trusted operator must disable this specific trigger in an isolated
-- restore target for that load, then re-enable it before application writes and
-- verify decryption with the matching Vault key. Full schema restores commonly
-- create triggers after loading data; inspect actual ordering. Default ORIGIN
-- triggers do not fire during logical replication apply.
-- ============================================================================

-- Function 1: safe_decrypt_twofa_secret
-- Safely attempts to decrypt the twofa_secret, returning NULL on failure
CREATE OR REPLACE FUNCTION "public"."safe_decrypt_twofa_secret"("encrypted_data" "text") RETURNS "text"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
BEGIN
    IF encrypted_data IS NULL THEN
        RETURN NULL;
    END IF;

    IF encrypted_data LIKE 'v1:%' THEN
        BEGIN
            RETURN public.decrypt_text(substring(encrypted_data from 4));
        EXCEPTION
            WHEN OTHERS THEN
                RETURN NULL;
        END;
    END IF;

    -- Unrecognized format (e.g., plaintext if not migrated, or corrupted)
    RETURN NULL;
END;
$$;

ALTER FUNCTION "public"."safe_decrypt_twofa_secret"("encrypted_data" "text") OWNER TO "postgres";
COMMENT ON FUNCTION "public"."safe_decrypt_twofa_secret"("encrypted_data" "text") IS 'Safely decrypt v1: prefixed 2FA TOTP secret, returning NULL on failure';

-- Prevent this from acting as an open generic decryption oracle for authenticated users.
-- Service-key callers also use an authorized RPC, not this raw decrypt helper.
REVOKE EXECUTE ON FUNCTION "public"."safe_decrypt_twofa_secret"("encrypted_data" "text") FROM PUBLIC, anon, authenticated, service_role;
GRANT EXECUTE ON FUNCTION "public"."safe_decrypt_twofa_secret"("encrypted_data" "text") TO "postgres";

-- Function 2: Trigger function to intercept and encrypt plaintext inserts/updates
-- Writers must use plain INSERT or UPDATE for a changed twofa_secret.
-- ON CONFLICT DO UPDATE SET twofa_secret = excluded.twofa_secret carries the
-- BEFORE INSERT trigger's envelope into UPDATE and is rejected when it differs.
-- Do not weaken envelope rejection to accommodate upserts: that permits a caller
-- to import another row's ciphertext into a row they can decrypt via the RPC.
-- PostgREST writers should use ordinary POST/PATCH, not merge-duplicates for a
-- changed seed. A custom SQL upsert assigning plaintext instead of EXCLUDED
-- does not carry the generated envelope and is a different, supported case.
CREATE OR REPLACE FUNCTION "public"."encrypt_twofa_secret_trigger_fn"() RETURNS trigger
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
BEGIN
    -- A no-op update may carry this row's stored ciphertext. Every changed value
    -- is plaintext input; never let a caller import another row's ciphertext and
    -- decrypt it through an otherwise authorized RPC on their own row.
    IF TG_OP = 'UPDATE' THEN
        IF NEW.twofa_secret IS NOT DISTINCT FROM OLD.twofa_secret THEN
            RETURN NEW;
        END IF;
    END IF;
    IF NEW.twofa_secret LIKE 'v1:%' THEN
        RAISE EXCEPTION 'TOTP input must be plaintext, not a storage envelope'
            USING ERRCODE = '22023';
    END IF;
    IF NEW.twofa_secret IS NOT NULL THEN
        NEW.twofa_secret := 'v1:' || public.encrypt_text(NEW.twofa_secret);
    END IF;
    RETURN NEW;
END;
$$;

ALTER FUNCTION "public"."encrypt_twofa_secret_trigger_fn"() OWNER TO "postgres";

REVOKE EXECUTE ON FUNCTION public.encrypt_twofa_secret_trigger_fn()
    FROM PUBLIC, anon, authenticated, service_role;

-- Backfill before installing the input trigger. Reapplication leaves stored
-- envelopes unchanged. No live database is needed to validate this migration.
DROP TRIGGER IF EXISTS encrypt_twofa_secret_trigger ON public.secret_metadata;
UPDATE public.secret_metadata
SET twofa_secret = 'v1:' || public.encrypt_text(twofa_secret)
WHERE twofa_secret IS NOT NULL AND twofa_secret NOT LIKE 'v1:%';

CREATE TRIGGER encrypt_twofa_secret_trigger
    BEFORE INSERT OR UPDATE OF twofa_secret ON public.secret_metadata
    FOR EACH ROW EXECUTE FUNCTION public.encrypt_twofa_secret_trigger_fn();

-- Replace the CURRENT RPC in this forward migration. Editing historical
-- migrations does not update an installed database. Preserve paging and all
-- five authorization sources from 20260907000000_secrets_paging_tiebreaker.sql.
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
                'twofa_secret', public.safe_decrypt_twofa_secret(sm.twofa_secret),
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

-- Restate the existing RPC grants, as the paging migration does.
GRANT EXECUTE ON FUNCTION public.get_user_secrets_with_shared(integer,integer)
    TO authenticated, service_role;
