-- Fluck vault: key rotation needs the current key, and minting needs an operator's approval.
--
-- 1. Rotation. `fluck_vault_register_instance` (and so the session-scoped
--    `fluck_vault_register_my_instance`) no longer changes the keys of an install that exists.
--    A session alone could otherwise swap in keys it holds, and every unconsumed link of that
--    install would seal to them. It returns 'rotation_requires_proof' instead. Keys change only
--    through `fluck_vault_rotate_instance` (service_role), which the edge function calls after
--    verifying a signature made with the install's CURRENT link key, as a compare-and-swap on
--    that key. The previous link key and the time are kept on the row.
--
-- 2. Issuance. A registered install may not create request rows until an operator approves
--    it (`fluck_vault_set_instance_issuance`, service_role only). Enforced by a trigger on
--    `fluck_vault_requests`, so it holds whatever signature `fluck_vault_create` has. Rows with
--    a NULL `instance_id` are the operator's own environment key and are unaffected.
--
-- Deploy order: this migration first, then the function. The function deployed before it
-- keeps working for legacy rows; an install's mint fails closed until it is approved.
-- Existing installs start unapproved.

ALTER TABLE "public"."fluck_vault_instances"
    ADD COLUMN IF NOT EXISTS "issuance_approved_at" timestamp with time zone,
    ADD COLUMN IF NOT EXISTS "rotated_at" timestamp with time zone,
    ADD COLUMN IF NOT EXISTS "previous_link_public_key" "text";

-- ---------------------------------------------------------------------------------------------
-- Registration: same signature, never a key change.
-- ---------------------------------------------------------------------------------------------

-- Returns 'ok' (new, or the same keys again), 'conflict' (another user's id), 'revoked',
-- 'limit', 'invalid' or 'rotation_requires_proof' (the id exists with different keys).
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
    v_row public.fluck_vault_instances%ROWTYPE;
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

    -- Serialises registrations per user, so the live-install ceiling below is not racy.
    PERFORM pg_advisory_xact_lock(hashtextextended('fluck_vault_instances:' || p_user_id::text, 0));

    SELECT * INTO v_row
    FROM public.fluck_vault_instances i
    WHERE i.instance_id = p_instance_id
    FOR UPDATE;

    IF FOUND THEN
        IF v_row.user_id <> p_user_id THEN
            RETURN 'conflict';
        END IF;
        IF v_row.revoked_at IS NOT NULL THEN
            RETURN 'revoked';
        END IF;
        IF v_row.link_public_key = p_link_public_key
           AND v_row.seal_public_key = p_seal_public_key THEN
            RETURN 'ok';
        END IF;
        RETURN 'rotation_requires_proof';
    END IF;

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
    IF NOT FOUND THEN
        RETURN 'conflict';
    END IF;
    RETURN 'ok';
END;
$$;

-- `fluck_vault_register_my_instance` is unchanged and inherits the rule above, so a plugin
-- session cannot re-key an install either. It can also return 'unauthorized' (no session).

-- ---------------------------------------------------------------------------------------------
-- Rotation: service_role only, after the caller verified a signature by the current link key.
-- ---------------------------------------------------------------------------------------------

-- Returns 'ok', 'conflict' (no such install for this user), 'revoked', 'stale' (the current
-- link key is no longer p_expected_link_public_key) or 'invalid'.
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

    UPDATE public.fluck_vault_instances
    SET link_public_key = p_link_public_key,
        seal_public_key = p_seal_public_key,
        previous_link_public_key = v_row.link_public_key,
        rotated_at = now()
    WHERE instance_id = p_instance_id;
    RETURN 'ok';
END;
$$;

ALTER FUNCTION "public"."fluck_vault_rotate_instance"("p_instance_id" "text", "p_user_id" "uuid", "p_expected_link_public_key" "text", "p_link_public_key" "text", "p_seal_public_key" "text")
    OWNER TO "postgres";

REVOKE ALL ON FUNCTION "public"."fluck_vault_rotate_instance"("p_instance_id" "text", "p_user_id" "uuid", "p_expected_link_public_key" "text", "p_link_public_key" "text", "p_seal_public_key" "text") FROM PUBLIC;
REVOKE ALL ON FUNCTION "public"."fluck_vault_rotate_instance"("p_instance_id" "text", "p_user_id" "uuid", "p_expected_link_public_key" "text", "p_link_public_key" "text", "p_seal_public_key" "text") FROM "anon";
REVOKE ALL ON FUNCTION "public"."fluck_vault_rotate_instance"("p_instance_id" "text", "p_user_id" "uuid", "p_expected_link_public_key" "text", "p_link_public_key" "text", "p_seal_public_key" "text") FROM "authenticated";
GRANT EXECUTE ON FUNCTION "public"."fluck_vault_rotate_instance"("p_instance_id" "text", "p_user_id" "uuid", "p_expected_link_public_key" "text", "p_link_public_key" "text", "p_seal_public_key" "text") TO "service_role";

-- ---------------------------------------------------------------------------------------------
-- Issuance approval: the operator's switch. service_role only; there is no user-facing path.
-- ---------------------------------------------------------------------------------------------

-- Returns true if the install exists and is live.
CREATE OR REPLACE FUNCTION "public"."fluck_vault_set_instance_issuance"(
    "p_instance_id" "text",
    "p_approved" boolean
) RETURNS boolean
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
AS $$
BEGIN
    UPDATE public.fluck_vault_instances
    SET issuance_approved_at = CASE WHEN p_approved THEN now() ELSE NULL END
    WHERE instance_id = p_instance_id
      AND revoked_at IS NULL;
    RETURN FOUND;
END;
$$;

ALTER FUNCTION "public"."fluck_vault_set_instance_issuance"("p_instance_id" "text", "p_approved" boolean)
    OWNER TO "postgres";

REVOKE ALL ON FUNCTION "public"."fluck_vault_set_instance_issuance"("p_instance_id" "text", "p_approved" boolean) FROM PUBLIC;
REVOKE ALL ON FUNCTION "public"."fluck_vault_set_instance_issuance"("p_instance_id" "text", "p_approved" boolean) FROM "anon";
REVOKE ALL ON FUNCTION "public"."fluck_vault_set_instance_issuance"("p_instance_id" "text", "p_approved" boolean) FROM "authenticated";
GRANT EXECUTE ON FUNCTION "public"."fluck_vault_set_instance_issuance"("p_instance_id" "text", "p_approved" boolean) TO "service_role";

-- A request row of an install needs that install approved. Independent of the create
-- function's signature, which a later migration may change.
CREATE OR REPLACE FUNCTION "public"."fluck_vault_requests_require_issuance"()
RETURNS trigger
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
AS $$
BEGIN
    IF NEW.instance_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM public.fluck_vault_instances i
        WHERE i.instance_id = NEW.instance_id
          AND i.revoked_at IS NULL
          AND i.issuance_approved_at IS NOT NULL
    ) THEN
        RAISE EXCEPTION 'fluck vault install is not approved for issuance'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN NEW;
END;
$$;

ALTER FUNCTION "public"."fluck_vault_requests_require_issuance"() OWNER TO "postgres";
REVOKE ALL ON FUNCTION "public"."fluck_vault_requests_require_issuance"() FROM PUBLIC;
REVOKE ALL ON FUNCTION "public"."fluck_vault_requests_require_issuance"() FROM "anon";
REVOKE ALL ON FUNCTION "public"."fluck_vault_requests_require_issuance"() FROM "authenticated";

DROP TRIGGER IF EXISTS "fluck_vault_requests_require_issuance" ON "public"."fluck_vault_requests";
CREATE TRIGGER "fluck_vault_requests_require_issuance"
    BEFORE INSERT OR UPDATE OF "instance_id" ON "public"."fluck_vault_requests"
    FOR EACH ROW EXECUTE FUNCTION "public"."fluck_vault_requests_require_issuance"();

-- ---------------------------------------------------------------------------------------------
-- Instance lookup: one more column. A changed return type needs a drop.
-- ---------------------------------------------------------------------------------------------

DROP FUNCTION IF EXISTS "public"."fluck_vault_instance"("p_instance_id" "text");

CREATE FUNCTION "public"."fluck_vault_instance"("p_instance_id" "text")
RETURNS TABLE (
    "user_id" "uuid",
    "link_public_key" "text",
    "seal_public_key" "text",
    "issuance_approved" boolean
)
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
AS $$
BEGIN
    RETURN QUERY
    SELECT i.user_id, i.link_public_key, i.seal_public_key,
           i.issuance_approved_at IS NOT NULL
    FROM public.fluck_vault_instances i
    WHERE i.instance_id = p_instance_id
      AND i.revoked_at IS NULL;
END;
$$;

ALTER FUNCTION "public"."fluck_vault_instance"("p_instance_id" "text") OWNER TO "postgres";

COMMENT ON FUNCTION "public"."fluck_vault_instance"("p_instance_id" "text") IS
    'One live Fluck install: its public keys and whether an operator approved it to mint links. No row if unknown or revoked. service_role only.';

REVOKE ALL ON FUNCTION "public"."fluck_vault_instance"("p_instance_id" "text") FROM PUBLIC;
REVOKE ALL ON FUNCTION "public"."fluck_vault_instance"("p_instance_id" "text") FROM "anon";
REVOKE ALL ON FUNCTION "public"."fluck_vault_instance"("p_instance_id" "text") FROM "authenticated";
GRANT EXECUTE ON FUNCTION "public"."fluck_vault_instance"("p_instance_id" "text") TO "service_role";

-- Amounts are ISO 4217 minor units (see supabase/functions/fluck-vault/currency.ts). Bounded
-- so they stay exact as a JSON number. NOT VALID: existing rows are short-lived and unchecked.
ALTER TABLE "public"."fluck_vault_requests"
    DROP CONSTRAINT IF EXISTS "fluck_vault_requests_total_cents_range";
ALTER TABLE "public"."fluck_vault_requests"
    ADD CONSTRAINT "fluck_vault_requests_total_cents_range"
    CHECK ("total_cents" IS NULL OR ("total_cents" >= 0 AND "total_cents" <= 1000000000000))
    NOT VALID;

COMMENT ON COLUMN "public"."fluck_vault_requests"."total_cents" IS
    'Amount in the ISO 4217 minor unit of currency (JPY: yen, USD: cents, KWD: fils). Not always hundredths, despite the name.';
