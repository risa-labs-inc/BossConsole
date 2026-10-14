-- Fluck vault: one key pair per Fluck install instead of one per project.
--
-- Until now every signed request and every link was checked against the single
-- `FLUCK_LINK_PUBLIC_KEY`, and every page sealed to the single `FLUCK_SEAL_PUBLIC_KEY`, so only
-- the one DGX holding those private keys could use the vault. An install now registers its own
-- public keys against the signed-in BOSS user, and names itself on each signed request with
-- `X-Fluck-Instance`. Rows it writes carry its `instance_id`; the page seals to that row's
-- instance key; a claim returns only that instance's rows.
--
-- Rows with a NULL `instance_id` are the legacy path and behave exactly as before, so the DGX
-- keeps working on the environment keys until it registers. Every signature below that changes
-- keeps its old call shape working (new parameters default to NULL), so the function version
-- deployed before this migration keeps working after it.

-- ---------------------------------------------------------------------------------------------
-- The installs
-- ---------------------------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS "public"."fluck_vault_instances" (
    "instance_id" "text" NOT NULL,
    "user_id" "uuid" NOT NULL,
    -- Ed25519, 32 raw bytes, standard base64.
    "link_public_key" "text" NOT NULL,
    -- P-256, uncompressed X9.62 point (65 bytes), standard base64.
    "seal_public_key" "text" NOT NULL,
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "revoked_at" timestamp with time zone,
    CONSTRAINT "fluck_vault_instances_pkey" PRIMARY KEY ("instance_id"),
    CONSTRAINT "fluck_vault_instances_user_id_fkey"
        FOREIGN KEY ("user_id") REFERENCES "auth"."users"("id") ON DELETE CASCADE,
    CONSTRAINT "fluck_vault_instances_id_check"
        CHECK ("instance_id" ~ '^[A-Za-z0-9_-]{16,64}$')
);

ALTER TABLE "public"."fluck_vault_instances" OWNER TO "postgres";

COMMENT ON TABLE "public"."fluck_vault_instances" IS
    'One row per Fluck install: the public halves of its link-signing and sealing keys, owned by one BOSS user. Public keys only. Written through fluck_vault_register_instance; service_role only.';

CREATE INDEX IF NOT EXISTS "idx_fluck_vault_instances_user_id"
    ON "public"."fluck_vault_instances" ("user_id");

-- RLS on with no policies: reachable by service_role and the definer functions below only.
ALTER TABLE "public"."fluck_vault_instances" ENABLE ROW LEVEL SECURITY;

REVOKE ALL ON TABLE "public"."fluck_vault_instances" FROM PUBLIC;
REVOKE ALL ON TABLE "public"."fluck_vault_instances" FROM "anon";
REVOKE ALL ON TABLE "public"."fluck_vault_instances" FROM "authenticated";
GRANT ALL ON TABLE "public"."fluck_vault_instances" TO "service_role";

-- ---------------------------------------------------------------------------------------------
-- Which install a request and a sealed blob belong to. NULL is the legacy environment keys.
-- ---------------------------------------------------------------------------------------------

ALTER TABLE "public"."fluck_vault_requests"
    ADD COLUMN IF NOT EXISTS "instance_id" "text"
        REFERENCES "public"."fluck_vault_instances"("instance_id") ON DELETE CASCADE;

ALTER TABLE "public"."fluck_vault_inbox"
    ADD COLUMN IF NOT EXISTS "instance_id" "text"
        REFERENCES "public"."fluck_vault_instances"("instance_id") ON DELETE CASCADE;

CREATE INDEX IF NOT EXISTS "idx_fluck_vault_requests_instance_id"
    ON "public"."fluck_vault_requests" ("instance_id");

CREATE INDEX IF NOT EXISTS "idx_fluck_vault_inbox_instance_ws_created"
    ON "public"."fluck_vault_inbox" ("instance_id", "ws", "created_at");

-- ---------------------------------------------------------------------------------------------
-- Registration
-- ---------------------------------------------------------------------------------------------

-- Upsert one install's keys for one user. Returns 'ok', 'conflict' (the id belongs to another
-- user), 'revoked', 'limit' (too many live installs for this user) or 'invalid'.
--
-- Key rotation for the same user and id is an update. A revoked install stays revoked.
CREATE OR REPLACE FUNCTION "public"."fluck_vault_register_instance"(
    "p_instance_id" "text",
    "p_user_id" "uuid",
    "p_link_public_key" "text",
    "p_seal_public_key" "text"
) RETURNS "text"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
AS $$
DECLARE
    v_link bytea;
    v_seal bytea;
    v_owner uuid;
    v_revoked timestamp with time zone;
BEGIN
    IF p_user_id IS NULL OR p_instance_id IS NULL
       OR p_instance_id !~ '^[A-Za-z0-9_-]{16,64}$' THEN
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

    SELECT i.user_id, i.revoked_at INTO v_owner, v_revoked
    FROM public.fluck_vault_instances i
    WHERE i.instance_id = p_instance_id
    FOR UPDATE;

    IF FOUND THEN
        IF v_owner <> p_user_id THEN
            RETURN 'conflict';
        END IF;
        IF v_revoked IS NOT NULL THEN
            RETURN 'revoked';
        END IF;
        UPDATE public.fluck_vault_instances
        SET link_public_key = p_link_public_key, seal_public_key = p_seal_public_key
        WHERE instance_id = p_instance_id;
        RETURN 'ok';
    END IF;

    -- Anyone signed in to BOSS can register, and a registered install can mint links on our
    -- domain. A ceiling per user keeps that from being free at scale.
    IF (SELECT count(*) FROM public.fluck_vault_instances i
        WHERE i.user_id = p_user_id AND i.revoked_at IS NULL) >= 10 THEN
        RETURN 'limit';
    END IF;

    INSERT INTO public.fluck_vault_instances (
        instance_id, user_id, link_public_key, seal_public_key
    ) VALUES (
        p_instance_id, p_user_id, p_link_public_key, p_seal_public_key
    )
    ON CONFLICT (instance_id) DO NOTHING;
    -- A concurrent insert of the same id won the race; its owner decides.
    IF NOT FOUND THEN
        RETURN 'conflict';
    END IF;
    RETURN 'ok';
END;
$$;

ALTER FUNCTION "public"."fluck_vault_register_instance"("p_instance_id" "text", "p_user_id" "uuid", "p_link_public_key" "text", "p_seal_public_key" "text")
    OWNER TO "postgres";

REVOKE ALL ON FUNCTION "public"."fluck_vault_register_instance"("p_instance_id" "text", "p_user_id" "uuid", "p_link_public_key" "text", "p_seal_public_key" "text") FROM PUBLIC;
REVOKE ALL ON FUNCTION "public"."fluck_vault_register_instance"("p_instance_id" "text", "p_user_id" "uuid", "p_link_public_key" "text", "p_seal_public_key" "text") FROM "anon";
REVOKE ALL ON FUNCTION "public"."fluck_vault_register_instance"("p_instance_id" "text", "p_user_id" "uuid", "p_link_public_key" "text", "p_seal_public_key" "text") FROM "authenticated";
GRANT EXECUTE ON FUNCTION "public"."fluck_vault_register_instance"("p_instance_id" "text", "p_user_id" "uuid", "p_link_public_key" "text", "p_seal_public_key" "text") TO "service_role";

-- The same, for the signed-in caller. The plugin API exposes an RPC that runs with the user's
-- session but not the access token itself, so this is how a plugin registers.
CREATE OR REPLACE FUNCTION "public"."fluck_vault_register_my_instance"(
    "p_instance_id" "text",
    "p_link_public_key" "text",
    "p_seal_public_key" "text"
) RETURNS "text"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
AS $$
BEGIN
    IF auth.uid() IS NULL THEN
        RETURN 'unauthorized';
    END IF;
    RETURN public.fluck_vault_register_instance(
        p_instance_id, auth.uid(), p_link_public_key, p_seal_public_key
    );
END;
$$;

ALTER FUNCTION "public"."fluck_vault_register_my_instance"("p_instance_id" "text", "p_link_public_key" "text", "p_seal_public_key" "text")
    OWNER TO "postgres";

REVOKE ALL ON FUNCTION "public"."fluck_vault_register_my_instance"("p_instance_id" "text", "p_link_public_key" "text", "p_seal_public_key" "text") FROM PUBLIC;
REVOKE ALL ON FUNCTION "public"."fluck_vault_register_my_instance"("p_instance_id" "text", "p_link_public_key" "text", "p_seal_public_key" "text") FROM "anon";
GRANT EXECUTE ON FUNCTION "public"."fluck_vault_register_my_instance"("p_instance_id" "text", "p_link_public_key" "text", "p_seal_public_key" "text") TO "authenticated";
GRANT EXECUTE ON FUNCTION "public"."fluck_vault_register_my_instance"("p_instance_id" "text", "p_link_public_key" "text", "p_seal_public_key" "text") TO "service_role";

-- One live install's keys, or no row if it is unknown or revoked.
CREATE OR REPLACE FUNCTION "public"."fluck_vault_instance"("p_instance_id" "text")
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

REVOKE ALL ON FUNCTION "public"."fluck_vault_instance"("p_instance_id" "text") FROM PUBLIC;
REVOKE ALL ON FUNCTION "public"."fluck_vault_instance"("p_instance_id" "text") FROM "anon";
REVOKE ALL ON FUNCTION "public"."fluck_vault_instance"("p_instance_id" "text") FROM "authenticated";
GRANT EXECUTE ON FUNCTION "public"."fluck_vault_instance"("p_instance_id" "text") TO "service_role";

-- ---------------------------------------------------------------------------------------------
-- The existing functions, instance aware. Old call shapes keep working.
-- ---------------------------------------------------------------------------------------------

-- Describe: two more columns, the row's instance and its keys. A revoked instance's rows no
-- longer render. A changed return type needs a drop.
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
    "instance_seal_public_key" "text"
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
           r.instance_id, i.link_public_key, i.seal_public_key
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

-- Store: same signature, now carries the request's instance onto the staged blob.
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
        instance_id
    ) VALUES (
        v_request.id, v_request.ws, v_request.purpose, v_request.kind, v_request.alias,
        v_request.purchase_id, p_ciphertext, p_cookie_hash, now() + v_ttl,
        v_request.instance_id
    )
    ON CONFLICT (jti) DO NOTHING;
    RETURN COALESCE(v_request.kind, 'cvv');
END;
$$;

-- Claim: one more parameter. NULL (and the old one-argument call) drains legacy rows only.
DROP FUNCTION IF EXISTS "public"."fluck_vault_claim"("p_ws" "text");

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
    "created_at" timestamp with time zone
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
    RETURNING i.id, i.jti, i.purpose, i.kind, i.alias, i.purchase_id, i.ciphertext, i.created_at;
END;
$$;

ALTER FUNCTION "public"."fluck_vault_claim"("p_ws" "text", "p_instance_id" "text") OWNER TO "postgres";

REVOKE ALL ON FUNCTION "public"."fluck_vault_claim"("p_ws" "text", "p_instance_id" "text") FROM PUBLIC;
REVOKE ALL ON FUNCTION "public"."fluck_vault_claim"("p_ws" "text", "p_instance_id" "text") FROM "anon";
REVOKE ALL ON FUNCTION "public"."fluck_vault_claim"("p_ws" "text", "p_instance_id" "text") FROM "authenticated";
GRANT EXECUTE ON FUNCTION "public"."fluck_vault_claim"("p_ws" "text", "p_instance_id" "text") TO "service_role";

-- Create: one more parameter, defaulted, so the twelve-argument call still resolves.
DROP FUNCTION IF EXISTS "public"."fluck_vault_create"("p_jti" "uuid", "p_ws" "text", "p_purpose" "text", "p_kind" "text", "p_alias" "text", "p_purchase_id" "text", "p_merchant" "text", "p_brand" "text", "p_last4" "text", "p_total_cents" bigint, "p_currency" "text", "p_expires_at" timestamp with time zone);

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
    "p_instance_id" "text" DEFAULT NULL
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
        merchant, brand, last4, total_cents, currency, expires_at, instance_id
    ) VALUES (
        p_jti, p_ws, p_purpose, p_kind, p_alias, p_purchase_id,
        p_merchant, p_brand, p_last4, p_total_cents, p_currency, p_expires_at, p_instance_id
    )
    ON CONFLICT (id) DO NOTHING;

    RETURN FOUND;
END;
$$;

ALTER FUNCTION "public"."fluck_vault_create"("p_jti" "uuid", "p_ws" "text", "p_purpose" "text", "p_kind" "text", "p_alias" "text", "p_purchase_id" "text", "p_merchant" "text", "p_brand" "text", "p_last4" "text", "p_total_cents" bigint, "p_currency" "text", "p_expires_at" timestamp with time zone, "p_instance_id" "text")
    OWNER TO "postgres";

REVOKE ALL ON FUNCTION "public"."fluck_vault_create"("p_jti" "uuid", "p_ws" "text", "p_purpose" "text", "p_kind" "text", "p_alias" "text", "p_purchase_id" "text", "p_merchant" "text", "p_brand" "text", "p_last4" "text", "p_total_cents" bigint, "p_currency" "text", "p_expires_at" timestamp with time zone, "p_instance_id" "text") FROM PUBLIC;
REVOKE ALL ON FUNCTION "public"."fluck_vault_create"("p_jti" "uuid", "p_ws" "text", "p_purpose" "text", "p_kind" "text", "p_alias" "text", "p_purchase_id" "text", "p_merchant" "text", "p_brand" "text", "p_last4" "text", "p_total_cents" bigint, "p_currency" "text", "p_expires_at" timestamp with time zone, "p_instance_id" "text") FROM "anon";
REVOKE ALL ON FUNCTION "public"."fluck_vault_create"("p_jti" "uuid", "p_ws" "text", "p_purpose" "text", "p_kind" "text", "p_alias" "text", "p_purchase_id" "text", "p_merchant" "text", "p_brand" "text", "p_last4" "text", "p_total_cents" bigint, "p_currency" "text", "p_expires_at" timestamp with time zone, "p_instance_id" "text") FROM "authenticated";
GRANT EXECUTE ON FUNCTION "public"."fluck_vault_create"("p_jti" "uuid", "p_ws" "text", "p_purpose" "text", "p_kind" "text", "p_alias" "text", "p_purchase_id" "text", "p_merchant" "text", "p_brand" "text", "p_last4" "text", "p_total_cents" bigint, "p_currency" "text", "p_expires_at" timestamp with time zone, "p_instance_id" "text") TO "service_role";
