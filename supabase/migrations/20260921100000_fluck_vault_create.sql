-- `fluck_vault_create`: the DGX writes its own request row, without holding a service role key.
--
-- The original polling contract had the DGX call PostgREST directly as `service_role`. That key
-- opens the whole project, and the box it would sit on also runs a model. The edge function now
-- offers two signed routes instead (`POST /requests`, `POST /inbox/claim`), authenticated with
-- the Ed25519 key the DGX already had to hold to sign links. This is the insert behind the
-- first of them.
--
-- It is an RPC rather than a table insert for the same reason `fluck_vault_describe` is: the
-- columns a caller can write are fixed by the signature, and a careless widening later has to
-- be a migration rather than an edit to a request body.
--
-- `consumed_at`, `created_at` and the expiry ceiling are NOT the caller's to set. The ceiling is
-- checked here as well as in the edge function, so a row can never outlive the longest token
-- that could possibly reach it.
CREATE OR REPLACE FUNCTION "public"."fluck_vault_create"(
    "p_jti" "uuid",
    "p_ws" "text",
    "p_purpose" "text",
    "p_kind" "text",
    "p_alias" "text",
    "p_purchase_id" "text",
    "p_merchant" "text",
    "p_brand" "text",
    "p_last4" "text",
    "p_total_cents" bigint,
    "p_currency" "text",
    "p_expires_at" timestamp with time zone
) RETURNS boolean
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
AS $$
DECLARE
    v_max interval;
BEGIN
    IF p_jti IS NULL OR p_ws IS NULL OR p_ws = '' OR p_expires_at IS NULL THEN
        RETURN false;
    END IF;
    IF p_purpose NOT IN ('vault', 'cvv') THEN
        RETURN false;
    END IF;

    v_max := CASE WHEN p_purpose = 'cvv' THEN interval '5 minutes' ELSE interval '10 minutes' END;
    IF p_expires_at <= now() OR p_expires_at > now() + v_max THEN
        RETURN false;
    END IF;

    -- Bounded housekeeping on a path that already writes. Minting is the only thing that
    -- happens often enough to be relied on for the sweep, and it is an index range scan.
    DELETE FROM public.fluck_vault_requests WHERE expires_at < now() - interval '1 hour';

    INSERT INTO public.fluck_vault_requests (
        id, ws, purpose, kind, alias, purchase_id,
        merchant, brand, last4, total_cents, currency, expires_at
    ) VALUES (
        p_jti, p_ws, p_purpose, p_kind, p_alias, p_purchase_id,
        p_merchant, p_brand, p_last4, p_total_cents, p_currency, p_expires_at
    )
    -- A replayed signed request is a duplicate id, not a second link.
    ON CONFLICT (id) DO NOTHING;

    RETURN FOUND;
END;
$$;

ALTER FUNCTION "public"."fluck_vault_create"("p_jti" "uuid", "p_ws" "text", "p_purpose" "text", "p_kind" "text", "p_alias" "text", "p_purchase_id" "text", "p_merchant" "text", "p_brand" "text", "p_last4" "text", "p_total_cents" bigint, "p_currency" "text", "p_expires_at" timestamp with time zone)
    OWNER TO "postgres";

REVOKE ALL ON FUNCTION "public"."fluck_vault_create"("p_jti" "uuid", "p_ws" "text", "p_purpose" "text", "p_kind" "text", "p_alias" "text", "p_purchase_id" "text", "p_merchant" "text", "p_brand" "text", "p_last4" "text", "p_total_cents" bigint, "p_currency" "text", "p_expires_at" timestamp with time zone) FROM PUBLIC;
REVOKE ALL ON FUNCTION "public"."fluck_vault_create"("p_jti" "uuid", "p_ws" "text", "p_purpose" "text", "p_kind" "text", "p_alias" "text", "p_purchase_id" "text", "p_merchant" "text", "p_brand" "text", "p_last4" "text", "p_total_cents" bigint, "p_currency" "text", "p_expires_at" timestamp with time zone) FROM "anon";
REVOKE ALL ON FUNCTION "public"."fluck_vault_create"("p_jti" "uuid", "p_ws" "text", "p_purpose" "text", "p_kind" "text", "p_alias" "text", "p_purchase_id" "text", "p_merchant" "text", "p_brand" "text", "p_last4" "text", "p_total_cents" bigint, "p_currency" "text", "p_expires_at" timestamp with time zone) FROM "authenticated";
GRANT EXECUTE ON FUNCTION "public"."fluck_vault_create"("p_jti" "uuid", "p_ws" "text", "p_purpose" "text", "p_kind" "text", "p_alias" "text", "p_purchase_id" "text", "p_merchant" "text", "p_brand" "text", "p_last4" "text", "p_total_cents" bigint, "p_currency" "text", "p_expires_at" timestamp with time zone) TO "service_role";

COMMENT ON FUNCTION "public"."fluck_vault_create"("p_jti" "uuid", "p_ws" "text", "p_purpose" "text", "p_kind" "text", "p_alias" "text", "p_purchase_id" "text", "p_merchant" "text", "p_brand" "text", "p_last4" "text", "p_total_cents" bigint, "p_currency" "text", "p_expires_at" timestamp with time zone) IS
    'Writes the request row for a link the DGX is about to sign. Non secret display facts only, expiry capped by purpose, duplicate ids ignored. service_role only; reached through the edge function signed route so the DGX holds no Supabase key.';
