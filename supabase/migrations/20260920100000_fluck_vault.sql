-- Fluck vault: the two tables and three functions the `fluck-vault` edge function needs.
--
-- ## The shape of the thing
--
-- The edge function serves a page on which the owner types a password, a card, or the security
-- code for one purchase. It is reachable by anyone, because the caller is a phone browser
-- following a link that arrived by text and carries no header we chose.
--
-- What makes that survivable is that the function CANNOT READ WHAT IT STORES. The page seals
-- the value in the browser to a public key whose private half lives only on the DGX, and this
-- schema stages the resulting ciphertext until the DGX drains it. Nothing here has a grant on
-- `public.secrets`, nothing here calls `encrypt_text` or `decrypt_text`, and a stolen service
-- role key yields a queue of blobs nobody holding it can open.
--
-- Two tables:
--
--   `fluck_vault_requests`  written by the DGX when it mints a link. Holds the display facts
--                           (merchant, last four, total) so they never have to travel in the
--                           token, which goes through a relay that sees plaintext.
--   `fluck_vault_inbox`     written by the edge function, drained and deleted by the DGX.
--
-- Three functions, all `service_role` only, EXECUTE revoked from `anon` and `authenticated`
-- explicitly rather than merely left ungranted, because PUBLIC gets EXECUTE on a new function
-- by default and an unrevoked `fluck_vault_store` would let any signed in user spend somebody
-- else's link.

-- ---------------------------------------------------------------------------------------------
-- The request the DGX minted
-- ---------------------------------------------------------------------------------------------

-- One row per minted link. `id` IS the token's `jti`, which is how a token with no merchant in
-- it still renders a page that names one.
--
-- Everything in this table is deliberately non secret: a merchant name, a last four, a total.
-- The card itself never appears here and neither does anything the owner types. `consumed_at`
-- is the single use marker, and it is set by `fluck_vault_store` in the same statement that
-- reads it, so two POSTs racing on one link cannot both win.
CREATE TABLE IF NOT EXISTS "public"."fluck_vault_requests" (
    "id" "uuid" NOT NULL,
    "ws" "text" NOT NULL,
    "purpose" "text" NOT NULL,
    "kind" "text",
    "alias" "text",
    "purchase_id" "text",
    "merchant" "text",
    "brand" "text",
    "last4" "text",
    "total_cents" bigint,
    "currency" "text",
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "expires_at" timestamp with time zone NOT NULL,
    "consumed_at" timestamp with time zone,
    CONSTRAINT "fluck_vault_requests_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "fluck_vault_requests_purpose_check"
        CHECK ("purpose" IN ('vault', 'cvv')),
    -- A vault request names a kind; a cvv request names a purchase. Enforced here rather than
    -- in the function, because the function verifies a signature and the database is what the
    -- signature's claims are checked AGAINST.
    CONSTRAINT "fluck_vault_requests_kind_check"
        CHECK (
            ("purpose" = 'vault' AND "kind" IN ('password', 'card') AND "purchase_id" IS NULL)
            OR ("purpose" = 'cvv' AND "kind" IS NULL AND "purchase_id" IS NOT NULL)
        ),
    -- Four digits at most, and only digits. A `last4` is rendered into a sentence on the CVV
    -- page; a column that could hold anything would be a place to put markup.
    CONSTRAINT "fluck_vault_requests_last4_check"
        CHECK ("last4" IS NULL OR "last4" ~ '^[0-9]{4}$'),
    CONSTRAINT "fluck_vault_requests_currency_check"
        CHECK ("currency" IS NULL OR "currency" ~ '^[A-Z]{3}$')
);

ALTER TABLE "public"."fluck_vault_requests" OWNER TO "postgres";

COMMENT ON TABLE "public"."fluck_vault_requests" IS
    'One row per vault or CVV link the DGX minted. Holds only non secret display facts, so the token itself can stay opaque on a relay that sees plaintext. Written by service_role only.';

CREATE INDEX IF NOT EXISTS "idx_fluck_vault_requests_expires_at"
    ON "public"."fluck_vault_requests" ("expires_at");

-- RLS on with NO policies: every role is denied and `service_role` bypasses RLS entirely. That
-- is the intended reach. Leaving RLS off would expose the table to `anon` through PostgREST.
ALTER TABLE "public"."fluck_vault_requests" ENABLE ROW LEVEL SECURITY;

REVOKE ALL ON TABLE "public"."fluck_vault_requests" FROM PUBLIC;
REVOKE ALL ON TABLE "public"."fluck_vault_requests" FROM "anon";
REVOKE ALL ON TABLE "public"."fluck_vault_requests" FROM "authenticated";
GRANT ALL ON TABLE "public"."fluck_vault_requests" TO "service_role";

-- ---------------------------------------------------------------------------------------------
-- The sealed blob, waiting to be collected
-- ---------------------------------------------------------------------------------------------

-- `ciphertext` is the whole payload and it is opaque to this database. See the edge function's
-- `seal.ts` for the byte layout: version byte, ephemeral P-256 public key, IV, AES-GCM output.
-- Nothing in Postgres can open it and nothing in Postgres should try.
--
-- `cookie_hash` records WHICH device finished the link, for an incident review. It is a hash,
-- so the cookie itself is not at rest, and it is evidence rather than a control: the control is
-- that the POST required the cookie in the first place.
CREATE TABLE IF NOT EXISTS "public"."fluck_vault_inbox" (
    "id" "uuid" DEFAULT "gen_random_uuid"() NOT NULL,
    "jti" "uuid" NOT NULL,
    "ws" "text" NOT NULL,
    "purpose" "text" NOT NULL,
    "kind" "text",
    "alias" "text",
    "purchase_id" "text",
    "ciphertext" "bytea" NOT NULL,
    "cookie_hash" "text",
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "expires_at" timestamp with time zone NOT NULL,
    "claimed_at" timestamp with time zone,
    CONSTRAINT "fluck_vault_inbox_pkey" PRIMARY KEY ("id"),
    -- One blob per link. A second POST on a spent link is already refused by `consumed_at`;
    -- this is the belt to that pair of braces, and it is what makes the insert idempotent under
    -- a retry that somehow got past the consume.
    CONSTRAINT "fluck_vault_inbox_jti_key" UNIQUE ("jti"),
    CONSTRAINT "fluck_vault_inbox_purpose_check"
        CHECK ("purpose" IN ('vault', 'cvv')),
    -- Eight kilobytes is a card with a full billing address and room to spare. A ceiling stops
    -- this table being used as free anonymous storage by anyone who gets hold of one link.
    CONSTRAINT "fluck_vault_inbox_size_check"
        CHECK ("octet_length"("ciphertext") BETWEEN 94 AND 8192)
);

ALTER TABLE "public"."fluck_vault_inbox" OWNER TO "postgres";

COMMENT ON TABLE "public"."fluck_vault_inbox" IS
    'Sealed values waiting for the DGX to collect them. Ciphertext only: the key that opens these rows is not in this project and never has been. Written by the fluck-vault edge function, drained by fluck_vault_claim.';

CREATE INDEX IF NOT EXISTS "idx_fluck_vault_inbox_ws_created"
    ON "public"."fluck_vault_inbox" ("ws", "created_at");
CREATE INDEX IF NOT EXISTS "idx_fluck_vault_inbox_expires_at"
    ON "public"."fluck_vault_inbox" ("expires_at");

ALTER TABLE "public"."fluck_vault_inbox" ENABLE ROW LEVEL SECURITY;

REVOKE ALL ON TABLE "public"."fluck_vault_inbox" FROM PUBLIC;
REVOKE ALL ON TABLE "public"."fluck_vault_inbox" FROM "anon";
REVOKE ALL ON TABLE "public"."fluck_vault_inbox" FROM "authenticated";
GRANT ALL ON TABLE "public"."fluck_vault_inbox" TO "service_role";

-- ---------------------------------------------------------------------------------------------
-- Describe: what the page is allowed to say out loud
-- ---------------------------------------------------------------------------------------------

-- The non secret half of a request, for rendering. It does NOT consume anything.
--
-- ## Why a GET must not consume
--
-- The link arrives by text. iMessage unfurls it, the relay's own preview fetcher unfurls it,
-- and any middlebox between the two may fetch it as well, all before a human has touched the
-- screen. A GET that spent the link would therefore hand the owner a dead link every single
-- time. So rendering is idempotent and free, and the single use property lives entirely on the
-- POST. (Red team C1.)
--
-- Expired and already consumed rows return nothing, which is how a stale link renders the same
-- fixed refusal as a forged one.
CREATE OR REPLACE FUNCTION "public"."fluck_vault_describe"("p_jti" "uuid")
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
    "currency" "text"
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
           r.merchant, r.brand, r.last4, r.total_cents, r.currency
    FROM public.fluck_vault_requests r
    WHERE r.id = p_jti
      AND r.consumed_at IS NULL
      AND r.expires_at > now();
END;
$$;

ALTER FUNCTION "public"."fluck_vault_describe"("p_jti" "uuid") OWNER TO "postgres";

REVOKE ALL ON FUNCTION "public"."fluck_vault_describe"("p_jti" "uuid") FROM PUBLIC;
REVOKE ALL ON FUNCTION "public"."fluck_vault_describe"("p_jti" "uuid") FROM "anon";
REVOKE ALL ON FUNCTION "public"."fluck_vault_describe"("p_jti" "uuid") FROM "authenticated";
GRANT EXECUTE ON FUNCTION "public"."fluck_vault_describe"("p_jti" "uuid") TO "service_role";

COMMENT ON FUNCTION "public"."fluck_vault_describe"("p_jti" "uuid") IS
    'Non secret display facts for one live request. Does not consume it: a GET on a texted link is unfurled by machines before a human sees it.';

-- ---------------------------------------------------------------------------------------------
-- Store: consume the link and stage the blob, atomically
-- ---------------------------------------------------------------------------------------------

-- Returns the kind that was stored ('password', 'card' or 'cvv'), or NULL if the link was
-- already spent, expired, or never existed.
--
-- ## Why the consume is an UPDATE with the predicate in its WHERE
--
-- `UPDATE … SET consumed_at = now() WHERE id = … AND consumed_at IS NULL` is decided by the row
-- lock, so two POSTs racing on one link produce exactly one row updated and one row not. A
-- SELECT followed by an UPDATE would let both through, which is the entire failure this
-- function exists to prevent. The insert into the inbox is in the SAME transaction, so there is
-- no state in which the link is spent and the value is not staged.
--
-- ## Why the caller's claims are not trusted
--
-- The edge function verified a signature over `jti`, `ws` and `purpose`, but it passes only the
-- `jti` here. Everything the inbox row records about the request is copied from the REQUEST
-- ROW, which the DGX wrote. So a token with a swapped `ws` claim cannot cross stage a value
-- into another workspace's queue: the workspace on the inbox row is the one the DGX minted
-- against. (Red team D4.)
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

    -- Bounded housekeeping on a path that already writes, so neither table can grow without
    -- bound on a deployment nobody remembered to add a cron to. Both are index range scans.
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

    -- A vault value is worth fifteen minutes of waiting; a CVV is worth ten, because a purchase
    -- that has not collected it by then has already failed and the row is nothing but risk.
    v_ttl := CASE WHEN v_request.purpose = 'cvv'
                  THEN interval '10 minutes'
                  ELSE interval '15 minutes' END;

    INSERT INTO public.fluck_vault_inbox (
        jti, ws, purpose, kind, alias, purchase_id, ciphertext, cookie_hash, expires_at
    ) VALUES (
        v_request.id, v_request.ws, v_request.purpose, v_request.kind, v_request.alias,
        v_request.purchase_id, p_ciphertext, p_cookie_hash, now() + v_ttl
    )
    ON CONFLICT (jti) DO NOTHING;

    RETURN COALESCE(v_request.kind, 'cvv');
END;
$$;

ALTER FUNCTION "public"."fluck_vault_store"("p_jti" "uuid", "p_ciphertext" "bytea", "p_cookie_hash" "text")
    OWNER TO "postgres";

REVOKE ALL ON FUNCTION "public"."fluck_vault_store"("p_jti" "uuid", "p_ciphertext" "bytea", "p_cookie_hash" "text") FROM PUBLIC;
REVOKE ALL ON FUNCTION "public"."fluck_vault_store"("p_jti" "uuid", "p_ciphertext" "bytea", "p_cookie_hash" "text") FROM "anon";
REVOKE ALL ON FUNCTION "public"."fluck_vault_store"("p_jti" "uuid", "p_ciphertext" "bytea", "p_cookie_hash" "text") FROM "authenticated";
GRANT EXECUTE ON FUNCTION "public"."fluck_vault_store"("p_jti" "uuid", "p_ciphertext" "bytea", "p_cookie_hash" "text") TO "service_role";

COMMENT ON FUNCTION "public"."fluck_vault_store"("p_jti" "uuid", "p_ciphertext" "bytea", "p_cookie_hash" "text") IS
    'Consumes one link and stages its sealed value in the same transaction. Returns the kind stored, or NULL if the link was spent, expired or unknown. service_role only.';

-- ---------------------------------------------------------------------------------------------
-- Claim: the DGX collects, in one statement
-- ---------------------------------------------------------------------------------------------

-- Return and DELETE every unclaimed row for one workspace, together.
--
-- ## Why DELETE … RETURNING and not SELECT then DELETE
--
-- If the poller crashes between reading a row and deleting it, a SELECT-then-DELETE leaves the
-- sealed blob sitting in the table until its TTL. Anyone holding the service key could then
-- keep a copy, and if the DGX private key ever leaked afterwards, that copy opens. Deleting in
-- the same statement that returns the rows means the value exists in exactly one place at a
-- time: in flight, or in the DGX's memory, never both. (Red team D6.)
--
-- The cost is that a crash between the delete committing and the DGX processing the rows loses
-- the value. That is the correct trade for a CVV: the owner retypes it, and nothing is at rest.
--
-- Rows that reached their TTL unclaimed are swept here and are NOT returned, so a value the
-- DGX was too slow for is destroyed rather than delivered late into a purchase that has moved on.
CREATE OR REPLACE FUNCTION "public"."fluck_vault_claim"("p_ws" "text")
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
      AND i.claimed_at IS NULL
      AND i.expires_at > now()
    RETURNING i.id, i.jti, i.purpose, i.kind, i.alias, i.purchase_id, i.ciphertext, i.created_at;
END;
$$;

ALTER FUNCTION "public"."fluck_vault_claim"("p_ws" "text") OWNER TO "postgres";

REVOKE ALL ON FUNCTION "public"."fluck_vault_claim"("p_ws" "text") FROM PUBLIC;
REVOKE ALL ON FUNCTION "public"."fluck_vault_claim"("p_ws" "text") FROM "anon";
REVOKE ALL ON FUNCTION "public"."fluck_vault_claim"("p_ws" "text") FROM "authenticated";
GRANT EXECUTE ON FUNCTION "public"."fluck_vault_claim"("p_ws" "text") TO "service_role";

COMMENT ON FUNCTION "public"."fluck_vault_claim"("p_ws" "text") IS
    'Returns and deletes every unclaimed sealed value for one workspace in one statement, so a crashed poller cannot leave a blob at rest. service_role only; the DGX calls it over HTTPS.';
