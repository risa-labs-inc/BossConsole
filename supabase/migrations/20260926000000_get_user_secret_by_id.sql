-- ============================================================================
-- BOSS Database Schema: one secret by id, for the host's reference resolver
-- ============================================================================
-- File: 20260926000000_get_user_secret_by_id.sql
--
-- The MCP secret-reference resolver (docs/MCP_SECRET_REFERENCES.md) turns
-- `{{secret:<id>}}` in an agent's tool arguments into the stored value at the
-- governance boundary. Until now the only way it could find one secret was
-- get_user_secrets, which has no by-id form, so it walked the listing page by
-- page: every secret up to the referenced one was decrypted server-side and
-- carried into host memory, and a reference to an id that does not exist
-- walked the whole vault (up to 25 pages of 200) before it was refused. Both
-- happen before any operator prompt, on the agent's say-so.
--
-- This function decrypts exactly the referenced row. Its visibility rule and
-- its row shape are get_user_secrets' own, so a secret is resolvable by id if
-- and only if it appears in the listing: the caller's personal secrets and the
-- secrets of organisations they are a member of, never a secret merely shared
-- with them (the resolver's documented v1 scope). Corrupt ciphertext is
-- blanked the same fail-soft way (20260914010000), not raised.
--
-- A miss is an empty result, not an error: the RPC does not distinguish "no
-- such id" from "not yours", so an agent cannot use it to probe which ids
-- exist in other vaults.
-- ============================================================================

CREATE OR REPLACE FUNCTION "public"."get_user_secret_by_id"("p_secret_id" "uuid")
RETURNS TABLE(
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
    WHERE s.id = p_secret_id
      AND (s.user_id = auth.uid()
           OR (s.org_id IS NOT NULL AND public.is_org_member(s.org_id)));
END;
$$;

ALTER FUNCTION "public"."get_user_secret_by_id"("uuid") OWNER TO "postgres";

COMMENT ON FUNCTION "public"."get_user_secret_by_id"("uuid") IS
    'One secret by id, decrypted, under exactly get_user_secrets'' visibility rule '
    'and row shape. Empty when the id is unknown or not visible to the caller. '
    'Added for the MCP secret-reference resolver so resolving one reference '
    'decrypts one row instead of walking the listing.';

-- Same client roles as the listing RPCs it mirrors (20260914010000), nothing wider.
REVOKE ALL ON FUNCTION "public"."get_user_secret_by_id"("uuid") FROM PUBLIC, "anon";
GRANT EXECUTE ON FUNCTION "public"."get_user_secret_by_id"("uuid") TO "authenticated", "service_role";
