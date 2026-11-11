-- Secret Manager hardening, before it backs Max vault cards and browser autofill.
-- Database only; no app release needed.
--
--   1. notes_encrypted: notes of card, cvv and address rows (or rows tagged
--      notes:encrypted) are sealed at rest by trigger, whatever the writer.
--      v1 listings read them back, so their signatures and output are unchanged.
--   2. secret_access_log survives deletion of its secret; delete_secret logs.
--
-- Existing rows are sealed by 20261111190100_seal_sensitive_secret_notes.sql.

-- ---------------------------------------------------------------------------
-- Access log: keep rows when the secret goes.
-- ---------------------------------------------------------------------------

ALTER TABLE public.secret_access_log
    DROP CONSTRAINT IF EXISTS secret_access_log_secret_id_fkey;

COMMENT ON COLUMN public.secret_access_log.secret_id IS
    'Secret id as a plain value, not a foreign key: audit rows outlive the secret.';

-- ---------------------------------------------------------------------------
-- Sealed notes.
-- ---------------------------------------------------------------------------

ALTER TABLE public.secrets
    ADD COLUMN IF NOT EXISTS notes_encrypted text;

-- No client column grant: 20260918000000 replaced table-level writes with
-- column-level ones, so a new column is not client-writable. Keep it that way;
-- a client-written ciphertext would turn the listings into a decryption oracle.

ALTER TABLE public.secrets
    DROP CONSTRAINT IF EXISTS secrets_notes_single_form;
ALTER TABLE public.secrets
    ADD CONSTRAINT secrets_notes_single_form CHECK (notes IS NULL OR notes_encrypted IS NULL);

COMMENT ON COLUMN public.secrets.notes_encrypted IS
    'Notes sealed with encrypt_text for sensitive rows (see secret_notes_sensitive). '
    'Written only by secrets_seal_notes_trigger_fn; notes is NULL when this is set.';

CREATE OR REPLACE FUNCTION public.secret_notes_sensitive(p_secret_id uuid)
RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path TO ''
    AS $$
    SELECT EXISTS (
        SELECT 1 FROM public.secret_tags st
        WHERE st.secret_id = p_secret_id
          AND st.tag IN ('kind:card', 'kind:cvv', 'kind:address', 'notes:encrypted')
    );
$$;

ALTER FUNCTION public.secret_notes_sensitive(uuid) OWNER TO postgres;
REVOKE ALL ON FUNCTION public.secret_notes_sensitive(uuid)
    FROM PUBLIC, anon, authenticated, service_role;

-- Fires on every write of notes. Tags are inserted after the row, so the tag
-- trigger below re-fires this one (SET notes = notes) once a sensitive tag lands.
CREATE OR REPLACE FUNCTION public.secrets_seal_notes_trigger_fn()
RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO ''
    AS $$
BEGIN
    IF NEW.notes IS NULL THEN
        -- A write that clears notes clears the sealed form too (v1 semantics).
        IF TG_OP = 'UPDATE' THEN
            NEW.notes_encrypted := NULL;
        END IF;
        RETURN NEW;
    END IF;
    IF public.secret_notes_sensitive(NEW.id) THEN
        NEW.notes_encrypted := public.encrypt_text(NEW.notes);
        NEW.notes := NULL;
    ELSE
        NEW.notes_encrypted := NULL;
    END IF;
    RETURN NEW;
END;
$$;

ALTER FUNCTION public.secrets_seal_notes_trigger_fn() OWNER TO postgres;
REVOKE ALL ON FUNCTION public.secrets_seal_notes_trigger_fn()
    FROM PUBLIC, anon, authenticated, service_role;

DROP TRIGGER IF EXISTS secrets_seal_notes_trigger ON public.secrets;
CREATE TRIGGER secrets_seal_notes_trigger
    BEFORE INSERT OR UPDATE OF notes ON public.secrets
    FOR EACH ROW EXECUTE FUNCTION public.secrets_seal_notes_trigger_fn();

CREATE OR REPLACE FUNCTION public.secret_tags_seal_notes_trigger_fn()
RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO ''
    AS $$
BEGIN
    UPDATE public.secrets SET notes = notes
    WHERE id = NEW.secret_id AND notes IS NOT NULL;
    RETURN NULL;
END;
$$;

ALTER FUNCTION public.secret_tags_seal_notes_trigger_fn() OWNER TO postgres;
REVOKE ALL ON FUNCTION public.secret_tags_seal_notes_trigger_fn()
    FROM PUBLIC, anon, authenticated, service_role;

DROP TRIGGER IF EXISTS secret_tags_seal_notes_trigger ON public.secret_tags;
CREATE TRIGGER secret_tags_seal_notes_trigger
    AFTER INSERT ON public.secret_tags
    FOR EACH ROW
    WHEN (NEW.tag IN ('kind:card', 'kind:cvv', 'kind:address', 'notes:encrypted'))
    EXECUTE FUNCTION public.secret_tags_seal_notes_trigger_fn();

-- ---------------------------------------------------------------------------
-- v1 listings: identical to 20260914010000 except notes reads the sealed form
-- back, so output is unchanged for every existing client.
-- ---------------------------------------------------------------------------

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
        COALESCE(public.try_decrypt_text(s.password_encrypted), '') AS password,
        COALESCE(s.notes, public.try_decrypt_text(s.notes_encrypted)) AS notes, s.expiration_date,
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
    WHERE s.user_id = auth.uid()
       OR (s.org_id IS NOT NULL AND public.is_org_member(s.org_id))
    ORDER BY s.created_at DESC, s.id DESC
    LIMIT p_limit OFFSET p_offset;
END;
$$;

ALTER FUNCTION "public"."get_user_secrets"(integer,integer) OWNER TO "postgres";


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
        COALESCE(public.try_decrypt_text(s.password_encrypted), '') AS password,
        COALESCE(s.notes, public.try_decrypt_text(s.notes_encrypted)) AS notes, s.expiration_date,
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
        COALESCE(public.try_decrypt_text(s.password_encrypted), '') AS password,
        COALESCE(s.notes, public.try_decrypt_text(s.notes_encrypted)) AS notes, s.expiration_date,
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

GRANT EXECUTE ON FUNCTION public.get_user_secrets(integer,integer)             TO authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.search_user_secrets(text,integer,integer)   TO authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.get_user_secrets_with_shared(integer,integer) TO authenticated, service_role;

-- ---------------------------------------------------------------------------
-- delete_secret: same signature and result; now leaves an audit row behind.
-- ---------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION public.delete_secret(p_secret_id uuid) RETURNS jsonb
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO ''
    AS $$
DECLARE
    v_secret public.secrets%ROWTYPE;
BEGIN
    IF auth.uid() IS NULL THEN
        RETURN jsonb_build_object('success', false, 'error', 'Not authenticated');
    END IF;

    IF NOT public.can_manage_secret(p_secret_id) THEN
        RETURN jsonb_build_object('success', false, 'error', 'Secret not found or access denied');
    END IF;

    DELETE FROM public.secrets WHERE id = p_secret_id RETURNING * INTO v_secret;

    IF NOT FOUND THEN
        RETURN jsonb_build_object('success', false, 'error', 'Secret not found or access denied');
    END IF;

    INSERT INTO public.secret_access_log (secret_id, user_id, operation, access_granted_via, metadata)
    VALUES (
        p_secret_id, auth.uid(), 'delete',
        CASE WHEN v_secret.user_id = auth.uid() THEN 'owner' ELSE 'admin_override' END,
        jsonb_build_object('org_id', v_secret.org_id, 'owner_user_id', v_secret.user_id)
    );

    RETURN jsonb_build_object('success', true, 'message', 'Secret deleted successfully');
EXCEPTION
    WHEN OTHERS THEN
        RETURN jsonb_build_object('success', false, 'error', SQLERRM);
END;
$$;

ALTER FUNCTION public.delete_secret(uuid) OWNER TO postgres;
