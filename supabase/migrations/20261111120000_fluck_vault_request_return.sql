-- Fluck vault: who asked, and where "Back to chat" goes.
--
-- The install may send two optional display facts when it mints a link: the agent's name and a
-- return URL. They only personalise the page; nothing reads them as authority. Both columns are
-- nullable and NULL on every existing row, and the edge function validates them (name 1 to 40
-- characters, URL https or loopback http, 512 characters at most) before writing; the checks
-- here are the backstop.
--
-- Additive. `fluck_vault_create` gains two defaulted parameters and `fluck_vault_describe` two
-- result columns, so the function deployed before this migration keeps working after it, and a
-- function deployed first retries the mint without the new parameters.
--
-- Deploy order: this migration, then `fluck-vault`.

ALTER TABLE "public"."fluck_vault_requests"
    ADD COLUMN IF NOT EXISTS "agent_name" "text",
    ADD COLUMN IF NOT EXISTS "return_url" "text";

ALTER TABLE "public"."fluck_vault_requests"
    DROP CONSTRAINT IF EXISTS "fluck_vault_requests_return_check";

ALTER TABLE "public"."fluck_vault_requests"
    ADD CONSTRAINT "fluck_vault_requests_return_check"
        CHECK (
            ("agent_name" IS NULL OR char_length("agent_name") BETWEEN 1 AND 40)
            AND ("return_url" IS NULL OR (char_length("return_url") <= 512
                 AND "return_url" ~ '^(https://|http://(localhost|127\.0\.0\.1|\[::1\])([:/?#]|$))'))
        ) NOT VALID;

-- ---------------------------------------------------------------------------------------------
-- Describe: two more result columns. A changed return type needs a drop.
-- ---------------------------------------------------------------------------------------------

DROP FUNCTION IF EXISTS "public"."fluck_vault_describe"("p_jti" "uuid");

CREATE FUNCTION "public"."fluck_vault_describe"("p_jti" "uuid")
RETURNS TABLE (
    "ws" "text",
    "purpose" "text",
    "kind" "text",
    "alias" "text",
    "purchase_id" "text",
    "merchant" "text",
    "brand" "text",
    "last4" "text",
    "total_cents" bigint,
    "currency" "text",
    "instance_id" "text",
    "instance_link_public_key" "text",
    "instance_seal_public_key" "text",
    "connector" "text",
    "agent_name" "text",
    "return_url" "text"
)
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
AS $$
BEGIN
    IF p_jti IS NULL THEN
        RETURN;
    END IF;
    RETURN QUERY
    SELECT r.ws, r.purpose, r.kind, r.alias, r.purchase_id,
           r.merchant, r.brand, r.last4, r.total_cents, r.currency,
           r.instance_id, i.link_public_key, i.seal_public_key,
           r.connector, r.agent_name, r.return_url
    FROM public.fluck_vault_requests r
    LEFT JOIN public.fluck_vault_instances i ON i.instance_id = r.instance_id
    WHERE r.id = p_jti
      AND r.consumed_at IS NULL
      AND r.expires_at > now()
      AND (r.instance_id IS NULL OR (i.instance_id IS NOT NULL AND i.revoked_at IS NULL));
END;
$$;

ALTER FUNCTION "public"."fluck_vault_describe"("p_jti" "uuid") OWNER TO "postgres";

REVOKE ALL ON FUNCTION "public"."fluck_vault_describe"("p_jti" "uuid") FROM PUBLIC;
REVOKE ALL ON FUNCTION "public"."fluck_vault_describe"("p_jti" "uuid") FROM "anon";
REVOKE ALL ON FUNCTION "public"."fluck_vault_describe"("p_jti" "uuid") FROM "authenticated";
GRANT EXECUTE ON FUNCTION "public"."fluck_vault_describe"("p_jti" "uuid") TO "service_role";

COMMENT ON FUNCTION "public"."fluck_vault_describe"("p_jti" "uuid") IS
    'The non secret half of a live, unspent, unexpired Fluck vault request, for rendering its page. No row for a revoked install. service_role only.';

-- ---------------------------------------------------------------------------------------------
-- Create: two more defaulted parameters. The old signature is dropped rather than overloaded:
-- two candidates would make the existing call ambiguous.
-- ---------------------------------------------------------------------------------------------

DROP FUNCTION IF EXISTS "public"."fluck_vault_create"("p_jti" "uuid", "p_ws" "text", "p_purpose" "text", "p_kind" "text", "p_alias" "text", "p_purchase_id" "text", "p_merchant" "text", "p_brand" "text", "p_last4" "text", "p_total_cents" bigint, "p_currency" "text", "p_expires_at" timestamp with time zone, "p_instance_id" "text", "p_connector" "text", "p_env" "text");

CREATE FUNCTION "public"."fluck_vault_create"(
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
    "p_expires_at" timestamp with time zone,
    "p_instance_id" "text" DEFAULT NULL,
    "p_connector" "text" DEFAULT NULL,
    "p_env" "text" DEFAULT NULL,
    "p_agent_name" "text" DEFAULT NULL,
    "p_return_url" "text" DEFAULT NULL
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
    IF p_instance_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM public.fluck_vault_instances i
        WHERE i.instance_id = p_instance_id AND i.revoked_at IS NULL
    ) THEN
        RETURN false;
    END IF;

    v_max := CASE WHEN p_purpose = 'cvv' THEN interval '5 minutes' ELSE interval '10 minutes' END;
    IF p_expires_at <= now() OR p_expires_at > now() + v_max THEN
        RETURN false;
    END IF;

    DELETE FROM public.fluck_vault_requests WHERE expires_at < now() - interval '1 hour';

    INSERT INTO public.fluck_vault_requests (
        id, ws, purpose, kind, alias, purchase_id,
        merchant, brand, last4, total_cents, currency, expires_at, instance_id, connector, env,
        agent_name, return_url
    ) VALUES (
        p_jti, p_ws, p_purpose, p_kind, p_alias, p_purchase_id,
        p_merchant, p_brand, p_last4, p_total_cents, p_currency, p_expires_at, p_instance_id,
        p_connector, p_env, p_agent_name, p_return_url
    )
    ON CONFLICT (id) DO NOTHING;

    RETURN FOUND;
END;
$$;

ALTER FUNCTION "public"."fluck_vault_create"("p_jti" "uuid", "p_ws" "text", "p_purpose" "text", "p_kind" "text", "p_alias" "text", "p_purchase_id" "text", "p_merchant" "text", "p_brand" "text", "p_last4" "text", "p_total_cents" bigint, "p_currency" "text", "p_expires_at" timestamp with time zone, "p_instance_id" "text", "p_connector" "text", "p_env" "text", "p_agent_name" "text", "p_return_url" "text")
    OWNER TO "postgres";

REVOKE ALL ON FUNCTION "public"."fluck_vault_create"("p_jti" "uuid", "p_ws" "text", "p_purpose" "text", "p_kind" "text", "p_alias" "text", "p_purchase_id" "text", "p_merchant" "text", "p_brand" "text", "p_last4" "text", "p_total_cents" bigint, "p_currency" "text", "p_expires_at" timestamp with time zone, "p_instance_id" "text", "p_connector" "text", "p_env" "text", "p_agent_name" "text", "p_return_url" "text") FROM PUBLIC;
REVOKE ALL ON FUNCTION "public"."fluck_vault_create"("p_jti" "uuid", "p_ws" "text", "p_purpose" "text", "p_kind" "text", "p_alias" "text", "p_purchase_id" "text", "p_merchant" "text", "p_brand" "text", "p_last4" "text", "p_total_cents" bigint, "p_currency" "text", "p_expires_at" timestamp with time zone, "p_instance_id" "text", "p_connector" "text", "p_env" "text", "p_agent_name" "text", "p_return_url" "text") FROM "anon";
REVOKE ALL ON FUNCTION "public"."fluck_vault_create"("p_jti" "uuid", "p_ws" "text", "p_purpose" "text", "p_kind" "text", "p_alias" "text", "p_purchase_id" "text", "p_merchant" "text", "p_brand" "text", "p_last4" "text", "p_total_cents" bigint, "p_currency" "text", "p_expires_at" timestamp with time zone, "p_instance_id" "text", "p_connector" "text", "p_env" "text", "p_agent_name" "text", "p_return_url" "text") FROM "authenticated";
GRANT EXECUTE ON FUNCTION "public"."fluck_vault_create"("p_jti" "uuid", "p_ws" "text", "p_purpose" "text", "p_kind" "text", "p_alias" "text", "p_purchase_id" "text", "p_merchant" "text", "p_brand" "text", "p_last4" "text", "p_total_cents" bigint, "p_currency" "text", "p_expires_at" timestamp with time zone, "p_instance_id" "text", "p_connector" "text", "p_env" "text", "p_agent_name" "text", "p_return_url" "text") TO "service_role";

COMMENT ON FUNCTION "public"."fluck_vault_create"("p_jti" "uuid", "p_ws" "text", "p_purpose" "text", "p_kind" "text", "p_alias" "text", "p_purchase_id" "text", "p_merchant" "text", "p_brand" "text", "p_last4" "text", "p_total_cents" bigint, "p_currency" "text", "p_expires_at" timestamp with time zone, "p_instance_id" "text", "p_connector" "text", "p_env" "text", "p_agent_name" "text", "p_return_url" "text") IS
    'Write the request row for a Fluck vault link about to be signed. False if the id is taken or the request is out of policy. service_role only.';
