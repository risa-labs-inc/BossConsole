-- Fluck vault: remove the operator-approved issuance gate added by 20261007100000.
--
-- Owner decision 2026-11-11: any registered, non-revoked install may mint links. Requiring hand
-- approval would block every Max install. Key rotation proof, the link expiry on rotation, the
-- owner list/revoke RPCs and the amount check stay.

DROP TRIGGER IF EXISTS "fluck_vault_requests_require_issuance" ON "public"."fluck_vault_requests";
DROP FUNCTION IF EXISTS "public"."fluck_vault_requests_require_issuance"();
DROP FUNCTION IF EXISTS "public"."fluck_vault_set_instance_issuance"("p_instance_id" "text", "p_approved" boolean);

-- Lookup: back to three columns (fluck-vault and fluck-oauth both call it). Return type changes.
DROP FUNCTION IF EXISTS "public"."fluck_vault_instance"("p_instance_id" "text");

CREATE FUNCTION "public"."fluck_vault_instance"("p_instance_id" "text")
RETURNS TABLE (
    "user_id" "uuid",
    "link_public_key" "text",
    "seal_public_key" "text"
)
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
AS $$
BEGIN
    RETURN QUERY
    SELECT i.user_id, i.link_public_key, i.seal_public_key
    FROM public.fluck_vault_instances i
    WHERE i.instance_id = p_instance_id
      AND i.revoked_at IS NULL;
END;
$$;

ALTER FUNCTION "public"."fluck_vault_instance"("p_instance_id" "text") OWNER TO "postgres";

COMMENT ON FUNCTION "public"."fluck_vault_instance"("p_instance_id" "text") IS
    'One live Fluck install: its public keys. No row if unknown or revoked. service_role only.';

REVOKE ALL ON FUNCTION "public"."fluck_vault_instance"("p_instance_id" "text") FROM PUBLIC;
REVOKE ALL ON FUNCTION "public"."fluck_vault_instance"("p_instance_id" "text") FROM "anon";
REVOKE ALL ON FUNCTION "public"."fluck_vault_instance"("p_instance_id" "text") FROM "authenticated";
GRANT EXECUTE ON FUNCTION "public"."fluck_vault_instance"("p_instance_id" "text") TO "service_role";

-- Owner list: without issuance_approved. Return type changes.
DROP FUNCTION IF EXISTS "public"."fluck_vault_my_instances"();

CREATE FUNCTION "public"."fluck_vault_my_instances"()
RETURNS TABLE (
    "instance_id" "text",
    "created_at" timestamp with time zone,
    "rotated_at" timestamp with time zone,
    "revoked_at" timestamp with time zone
)
    LANGUAGE "plpgsql" STABLE SECURITY DEFINER
    SET "search_path" TO ''
AS $$
BEGIN
    IF auth.uid() IS NULL THEN
        RETURN;
    END IF;
    RETURN QUERY
    SELECT i.instance_id, i.created_at, i.rotated_at, i.revoked_at
    FROM public.fluck_vault_instances i
    WHERE i.user_id = auth.uid()
    ORDER BY i.created_at DESC
    LIMIT 100;
END;
$$;

ALTER FUNCTION "public"."fluck_vault_my_instances"() OWNER TO "postgres";

COMMENT ON FUNCTION "public"."fluck_vault_my_instances"() IS
    'The signed-in caller''s Fluck vault installs, newest first, revoked ones included. No keys.';

REVOKE ALL ON FUNCTION "public"."fluck_vault_my_instances"() FROM PUBLIC;
REVOKE ALL ON FUNCTION "public"."fluck_vault_my_instances"() FROM "anon";
GRANT EXECUTE ON FUNCTION "public"."fluck_vault_my_instances"() TO "authenticated";
GRANT EXECUTE ON FUNCTION "public"."fluck_vault_my_instances"() TO "service_role";

-- Rotation: unchanged except it no longer touches issuance_approved_at. Links minted under the
-- old keys still expire.
CREATE OR REPLACE FUNCTION "public"."fluck_vault_rotate_instance"(
    "p_instance_id" "text",
    "p_user_id" "uuid",
    "p_expected_link_public_key" "text",
    "p_link_public_key" "text",
    "p_seal_public_key" "text"
) RETURNS "text"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
AS $$
DECLARE
    v_link bytea;
    v_seal bytea;
    v_row public.fluck_vault_instances%ROWTYPE;
BEGIN
    IF p_user_id IS NULL OR p_instance_id IS NULL OR p_expected_link_public_key IS NULL THEN
        RETURN 'invalid';
    END IF;
    BEGIN
        v_link := decode(p_link_public_key, 'base64');
        v_seal := decode(p_seal_public_key, 'base64');
    EXCEPTION WHEN OTHERS THEN
        RETURN 'invalid';
    END;
    IF v_link IS NULL OR octet_length(v_link) <> 32
       OR v_seal IS NULL OR octet_length(v_seal) <> 65 OR get_byte(v_seal, 0) <> 4 THEN
        RETURN 'invalid';
    END IF;

    SELECT * INTO v_row
    FROM public.fluck_vault_instances i
    WHERE i.instance_id = p_instance_id
    FOR UPDATE;

    IF NOT FOUND OR v_row.user_id <> p_user_id THEN
        RETURN 'conflict';
    END IF;
    IF v_row.revoked_at IS NOT NULL THEN
        RETURN 'revoked';
    END IF;
    IF v_row.link_public_key <> p_expected_link_public_key THEN
        RETURN 'stale';
    END IF;

    -- previous_link_public_key is a record only, never accepted as a proof.
    UPDATE public.fluck_vault_instances
    SET link_public_key = p_link_public_key,
        seal_public_key = p_seal_public_key,
        previous_link_public_key = v_row.link_public_key,
        rotated_at = now()
    WHERE instance_id = p_instance_id;
    -- Links minted under the old keys must not start sealing to the new seal key.
    UPDATE public.fluck_vault_requests
    SET expires_at = now()
    WHERE instance_id = p_instance_id
      AND consumed_at IS NULL
      AND expires_at > now();
    RETURN 'ok';
END;
$$;

ALTER FUNCTION "public"."fluck_vault_rotate_instance"("p_instance_id" "text", "p_user_id" "uuid", "p_expected_link_public_key" "text", "p_link_public_key" "text", "p_seal_public_key" "text")
    OWNER TO "postgres";

REVOKE ALL ON FUNCTION "public"."fluck_vault_rotate_instance"("p_instance_id" "text", "p_user_id" "uuid", "p_expected_link_public_key" "text", "p_link_public_key" "text", "p_seal_public_key" "text") FROM PUBLIC;
REVOKE ALL ON FUNCTION "public"."fluck_vault_rotate_instance"("p_instance_id" "text", "p_user_id" "uuid", "p_expected_link_public_key" "text", "p_link_public_key" "text", "p_seal_public_key" "text") FROM "anon";
REVOKE ALL ON FUNCTION "public"."fluck_vault_rotate_instance"("p_instance_id" "text", "p_user_id" "uuid", "p_expected_link_public_key" "text", "p_link_public_key" "text", "p_seal_public_key" "text") FROM "authenticated";
GRANT EXECUTE ON FUNCTION "public"."fluck_vault_rotate_instance"("p_instance_id" "text", "p_user_id" "uuid", "p_expected_link_public_key" "text", "p_link_public_key" "text", "p_seal_public_key" "text") TO "service_role";

-- Revoke: unchanged except it no longer touches issuance_approved_at.
CREATE OR REPLACE FUNCTION "public"."fluck_vault_revoke_my_instance"("p_instance_id" "text")
RETURNS "text"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
AS $$
DECLARE
    v_user uuid := auth.uid();
    v_row public.fluck_vault_instances%ROWTYPE;
BEGIN
    IF v_user IS NULL THEN
        RETURN 'unauthorized';
    END IF;
    PERFORM pg_advisory_xact_lock(hashtextextended('fluck_vault_instances:' || v_user::text, 0));
    SELECT * INTO v_row
    FROM public.fluck_vault_instances i
    WHERE i.instance_id = p_instance_id AND i.user_id = v_user
    FOR UPDATE;
    IF NOT FOUND THEN
        RETURN 'not_found';
    END IF;
    IF v_row.revoked_at IS NOT NULL THEN
        RETURN 'ok';
    END IF;
    UPDATE public.fluck_vault_instances
    SET revoked_at = now()
    WHERE instance_id = p_instance_id;
    UPDATE public.fluck_vault_requests
    SET expires_at = now()
    WHERE instance_id = p_instance_id
      AND consumed_at IS NULL
      AND expires_at > now();
    DELETE FROM public.fluck_vault_inbox WHERE instance_id = p_instance_id;
    RETURN 'ok';
END;
$$;

ALTER FUNCTION "public"."fluck_vault_revoke_my_instance"("p_instance_id" "text") OWNER TO "postgres";

REVOKE ALL ON FUNCTION "public"."fluck_vault_revoke_my_instance"("p_instance_id" "text") FROM PUBLIC;
REVOKE ALL ON FUNCTION "public"."fluck_vault_revoke_my_instance"("p_instance_id" "text") FROM "anon";
GRANT EXECUTE ON FUNCTION "public"."fluck_vault_revoke_my_instance"("p_instance_id" "text") TO "authenticated";
GRANT EXECUTE ON FUNCTION "public"."fluck_vault_revoke_my_instance"("p_instance_id" "text") TO "service_role";

ALTER TABLE "public"."fluck_vault_instances" DROP COLUMN IF EXISTS "issuance_approved_at";
