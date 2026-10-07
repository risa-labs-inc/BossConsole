-- Fluck vault: a third vault kind, `secret`, for API keys and connector tokens.
--
-- The page seals `{label, value}` exactly as it seals a login or a card. What is new is two
-- NON secret facts the install asks for when it mints the link, carried on the request and
-- handed back beside the blob on claim:
--
--   `connector`  which connector the value is for, so the page can label the field itself
--   `env`        the environment variable name the install will expose the value as
--
-- Additive. Both columns are nullable and NULL on every existing row; the kind check admits
-- `secret` and no longer admits a vault row with a NULL kind. Every function keeps its old call shape (new parameters default to
-- NULL) and old callers ignore the added result columns, so the edge function deployed before
-- this migration keeps working after it.
--
-- Runs after 20261007100000 (rotation and issuance) and redefines none of its functions. The
-- issuance trigger, rotation expiry and revocation act on every request row, `secret` included.
--
-- Deploy order: this migration, then `fluck-vault`. A function deployed first answers every
-- secret mint with 503 'schema' rather than a misleading 409 'taken'.

-- ---------------------------------------------------------------------------------------------
-- The columns
-- ---------------------------------------------------------------------------------------------

ALTER TABLE "public"."fluck_vault_requests"
    ADD COLUMN IF NOT EXISTS "connector" "text",
    ADD COLUMN IF NOT EXISTS "env" "text";

ALTER TABLE "public"."fluck_vault_inbox"
    ADD COLUMN IF NOT EXISTS "connector" "text",
    ADD COLUMN IF NOT EXISTS "env" "text";

ALTER TABLE "public"."fluck_vault_requests"
    DROP CONSTRAINT IF EXISTS "fluck_vault_requests_kind_check";

-- Every branch is TRUE or FALSE, never NULL: a CHECK passes on NULL, so `"kind" IN (...)` on a
-- NULL kind would admit a vault row without one. NOT VALID: existing rows are short-lived.
ALTER TABLE "public"."fluck_vault_requests"
    ADD CONSTRAINT "fluck_vault_requests_kind_check"
        CHECK (
            ("purpose" = 'vault' AND "kind" IS NOT NULL
                AND "kind" IN ('password', 'card', 'secret') AND "purchase_id" IS NULL)
            OR ("purpose" = 'cvv' AND "kind" IS NULL AND "purchase_id" IS NOT NULL)
        ) NOT VALID;

-- Only a secret carries metadata, and only from a fixed vocabulary: `connector` is rendered
-- into the page and `env` becomes a variable name on the install. Per kind, so a cvv row (kind
-- NULL) is held to "no metadata" too. The vocabulary is pinned to app.ts by a test.
ALTER TABLE "public"."fluck_vault_requests"
    DROP CONSTRAINT IF EXISTS "fluck_vault_requests_secret_meta_check";

ALTER TABLE "public"."fluck_vault_requests"
    ADD CONSTRAINT "fluck_vault_requests_secret_meta_check"
        CHECK (
            CASE WHEN "kind" IS NOT DISTINCT FROM 'secret'
                THEN ("connector" IS NULL
                      OR "connector" IN ('notion', 'github', 'google', 'gmail', 'calendar', 'workspace'))
                     AND ("env" IS NULL OR "env" ~ '^[A-Z][A-Z0-9_]{1,63}$')
                ELSE "connector" IS NULL AND "env" IS NULL
            END
        );

-- The inbox is written only by fluck_vault_store from a checked request row; this holds it there.
ALTER TABLE "public"."fluck_vault_inbox"
    DROP CONSTRAINT IF EXISTS "fluck_vault_inbox_secret_meta_check";

ALTER TABLE "public"."fluck_vault_inbox"
    ADD CONSTRAINT "fluck_vault_inbox_secret_meta_check"
        CHECK ("kind" IS NOT DISTINCT FROM 'secret' OR ("connector" IS NULL AND "env" IS NULL));

-- ---------------------------------------------------------------------------------------------
-- Describe: the connector, so the page can label the field. `env` is not needed to render.
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
    "connector" "text"
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
           r.connector
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
-- Store: same signature; now carries the metadata onto the staged blob.
-- ---------------------------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION "public"."fluck_vault_store"(
    "p_jti" "uuid",
    "p_ciphertext" "bytea",
    "p_cookie_hash" "text"
) RETURNS "text"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
AS $$
DECLARE
    v_request public.fluck_vault_requests%ROWTYPE;
    v_ttl interval;
BEGIN
    IF p_jti IS NULL OR p_ciphertext IS NULL THEN
        RETURN NULL;
    END IF;
    IF octet_length(p_ciphertext) < 94 OR octet_length(p_ciphertext) > 8192 THEN
        RETURN NULL;
    END IF;
    DELETE FROM public.fluck_vault_inbox WHERE expires_at < now();
    DELETE FROM public.fluck_vault_requests
    WHERE expires_at < now() - interval '1 hour';
    UPDATE public.fluck_vault_requests
    SET consumed_at = now()
    WHERE id = p_jti
      AND consumed_at IS NULL
      AND expires_at > now()
    RETURNING * INTO v_request;
    IF NOT FOUND THEN
        RETURN NULL;
    END IF;
    v_ttl := CASE WHEN v_request.purpose = 'cvv'
                  THEN interval '10 minutes'
                  ELSE interval '15 minutes' END;
    INSERT INTO public.fluck_vault_inbox (
        jti, ws, purpose, kind, alias, purchase_id, ciphertext, cookie_hash, expires_at,
        instance_id, connector, env
    ) VALUES (
        v_request.id, v_request.ws, v_request.purpose, v_request.kind, v_request.alias,
        v_request.purchase_id, p_ciphertext, p_cookie_hash, now() + v_ttl,
        v_request.instance_id, v_request.connector, v_request.env
    )
    ON CONFLICT (jti) DO NOTHING;
    RETURN COALESCE(v_request.kind, 'cvv');
END;
$$;

COMMENT ON FUNCTION "public"."fluck_vault_store"("p_jti" "uuid", "p_ciphertext" "bytea", "p_cookie_hash" "text") IS
    'Consume a Fluck vault request and stage its sealed blob with the request''s metadata. Returns the kind stored, or NULL if the request is gone. service_role only.';

-- ---------------------------------------------------------------------------------------------
-- Claim: two more result columns. A changed return type needs a drop.
-- ---------------------------------------------------------------------------------------------

DROP FUNCTION IF EXISTS "public"."fluck_vault_claim"("p_ws" "text", "p_instance_id" "text");

CREATE FUNCTION "public"."fluck_vault_claim"(
    "p_ws" "text",
    "p_instance_id" "text" DEFAULT NULL
)
RETURNS TABLE (
    "id" "uuid",
    "jti" "uuid",
    "purpose" "text",
    "kind" "text",
    "alias" "text",
    "purchase_id" "text",
    "ciphertext" "bytea",
    "created_at" timestamp with time zone,
    "connector" "text",
    "env" "text"
)
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
AS $$
BEGIN
    IF p_ws IS NULL OR p_ws = '' THEN
        RETURN;
    END IF;
    DELETE FROM public.fluck_vault_inbox WHERE expires_at < now();
    RETURN QUERY
    DELETE FROM public.fluck_vault_inbox i
    WHERE i.ws = p_ws
      AND i.instance_id IS NOT DISTINCT FROM p_instance_id
      AND i.claimed_at IS NULL
      AND i.expires_at > now()
    RETURNING i.id, i.jti, i.purpose, i.kind, i.alias, i.purchase_id, i.ciphertext, i.created_at,
              i.connector, i.env;
END;
$$;

ALTER FUNCTION "public"."fluck_vault_claim"("p_ws" "text", "p_instance_id" "text") OWNER TO "postgres";

REVOKE ALL ON FUNCTION "public"."fluck_vault_claim"("p_ws" "text", "p_instance_id" "text") FROM PUBLIC;
REVOKE ALL ON FUNCTION "public"."fluck_vault_claim"("p_ws" "text", "p_instance_id" "text") FROM "anon";
REVOKE ALL ON FUNCTION "public"."fluck_vault_claim"("p_ws" "text", "p_instance_id" "text") FROM "authenticated";
GRANT EXECUTE ON FUNCTION "public"."fluck_vault_claim"("p_ws" "text", "p_instance_id" "text") TO "service_role";

COMMENT ON FUNCTION "public"."fluck_vault_claim"("p_ws" "text", "p_instance_id" "text") IS
    'Return and delete the unclaimed staged blobs of one workspace and install (NULL: legacy rows). service_role only.';

-- ---------------------------------------------------------------------------------------------
-- Create: two more parameters, defaulted, so the thirteen-argument call still resolves. The old
-- signature is dropped rather than overloaded: two candidates would make that call ambiguous.
-- ---------------------------------------------------------------------------------------------

DROP FUNCTION IF EXISTS "public"."fluck_vault_create"("p_jti" "uuid", "p_ws" "text", "p_purpose" "text", "p_kind" "text", "p_alias" "text", "p_purchase_id" "text", "p_merchant" "text", "p_brand" "text", "p_last4" "text", "p_total_cents" bigint, "p_currency" "text", "p_expires_at" timestamp with time zone, "p_instance_id" "text");

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
    "p_env" "text" DEFAULT NULL
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
        merchant, brand, last4, total_cents, currency, expires_at, instance_id, connector, env
    ) VALUES (
        p_jti, p_ws, p_purpose, p_kind, p_alias, p_purchase_id,
        p_merchant, p_brand, p_last4, p_total_cents, p_currency, p_expires_at, p_instance_id,
        p_connector, p_env
    )
    ON CONFLICT (id) DO NOTHING;

    RETURN FOUND;
END;
$$;

ALTER FUNCTION "public"."fluck_vault_create"("p_jti" "uuid", "p_ws" "text", "p_purpose" "text", "p_kind" "text", "p_alias" "text", "p_purchase_id" "text", "p_merchant" "text", "p_brand" "text", "p_last4" "text", "p_total_cents" bigint, "p_currency" "text", "p_expires_at" timestamp with time zone, "p_instance_id" "text", "p_connector" "text", "p_env" "text")
    OWNER TO "postgres";

REVOKE ALL ON FUNCTION "public"."fluck_vault_create"("p_jti" "uuid", "p_ws" "text", "p_purpose" "text", "p_kind" "text", "p_alias" "text", "p_purchase_id" "text", "p_merchant" "text", "p_brand" "text", "p_last4" "text", "p_total_cents" bigint, "p_currency" "text", "p_expires_at" timestamp with time zone, "p_instance_id" "text", "p_connector" "text", "p_env" "text") FROM PUBLIC;
REVOKE ALL ON FUNCTION "public"."fluck_vault_create"("p_jti" "uuid", "p_ws" "text", "p_purpose" "text", "p_kind" "text", "p_alias" "text", "p_purchase_id" "text", "p_merchant" "text", "p_brand" "text", "p_last4" "text", "p_total_cents" bigint, "p_currency" "text", "p_expires_at" timestamp with time zone, "p_instance_id" "text", "p_connector" "text", "p_env" "text") FROM "anon";
REVOKE ALL ON FUNCTION "public"."fluck_vault_create"("p_jti" "uuid", "p_ws" "text", "p_purpose" "text", "p_kind" "text", "p_alias" "text", "p_purchase_id" "text", "p_merchant" "text", "p_brand" "text", "p_last4" "text", "p_total_cents" bigint, "p_currency" "text", "p_expires_at" timestamp with time zone, "p_instance_id" "text", "p_connector" "text", "p_env" "text") FROM "authenticated";
GRANT EXECUTE ON FUNCTION "public"."fluck_vault_create"("p_jti" "uuid", "p_ws" "text", "p_purpose" "text", "p_kind" "text", "p_alias" "text", "p_purchase_id" "text", "p_merchant" "text", "p_brand" "text", "p_last4" "text", "p_total_cents" bigint, "p_currency" "text", "p_expires_at" timestamp with time zone, "p_instance_id" "text", "p_connector" "text", "p_env" "text") TO "service_role";

COMMENT ON FUNCTION "public"."fluck_vault_create"("p_jti" "uuid", "p_ws" "text", "p_purpose" "text", "p_kind" "text", "p_alias" "text", "p_purchase_id" "text", "p_merchant" "text", "p_brand" "text", "p_last4" "text", "p_total_cents" bigint, "p_currency" "text", "p_expires_at" timestamp with time zone, "p_instance_id" "text", "p_connector" "text", "p_env" "text") IS
    'Write the request row for a Fluck vault link about to be signed. False if the id is taken or the request is out of policy. service_role only.';
