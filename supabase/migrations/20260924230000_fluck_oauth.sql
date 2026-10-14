-- Fluck Google connector: the two pieces of server state the `fluck-oauth` edge function needs.
--
-- The function is the OAuth redirect target for Fluck's Google connector. It is reachable by
-- anyone, because the caller is a phone browser following a redirect Google issued and carries
-- no header we chose, so the signed `state` parameter is its entire authentication. Two things
-- in this file make that hold:
--
--   1. `fluck_oauth_nonces` + `fluck_oauth_claim_nonce`, so a state is spendable exactly once.
--   2. `fluck_oauth_store_secret`, so the refresh token is encrypted inside the database and
--      replaces any earlier grant for the same key in ONE transaction.
--
-- Both are `service_role` only. EXECUTE is revoked from `anon` and `authenticated` explicitly,
-- not merely left ungranted, because PUBLIC gets EXECUTE on new functions by default and an
-- unrevoked `fluck_oauth_store_secret` would let any signed in user write an encrypted row as
-- any other user id they cared to name.

-- ---------------------------------------------------------------------------------------------
-- Replay protection
-- ---------------------------------------------------------------------------------------------

-- One row per state nonce that has been spent.
--
-- The primary key IS the mechanism: two callbacks racing on the same link are two concurrent
-- inserts, and exactly one of them wins. A read followed by a write from the function would let
-- both through, which is the whole failure this table exists to prevent.
--
-- `expires_at` is the state's own expiry, copied in, so the table can be swept without knowing
-- anything about the tokens. Rows are only useful until then: a state past its expiry is already
-- refused by the signature check, so keeping its nonce proves nothing.
CREATE TABLE IF NOT EXISTS "public"."fluck_oauth_nonces" (
    "nonce" "text" NOT NULL,
    "expires_at" timestamp with time zone NOT NULL,
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    CONSTRAINT "fluck_oauth_nonces_pkey" PRIMARY KEY ("nonce")
);

ALTER TABLE "public"."fluck_oauth_nonces" OWNER TO "postgres";

COMMENT ON TABLE "public"."fluck_oauth_nonces" IS
    'Spent OAuth state nonces for the fluck-oauth edge function. Written only by service_role, through fluck_oauth_claim_nonce.';

CREATE INDEX IF NOT EXISTS "idx_fluck_oauth_nonces_expires_at"
    ON "public"."fluck_oauth_nonces" ("expires_at");

-- RLS on with NO policies: every role is denied, and `service_role` bypasses RLS entirely. That
-- is the intended reach. Leaving RLS off would expose the table to `anon` through PostgREST.
ALTER TABLE "public"."fluck_oauth_nonces" ENABLE ROW LEVEL SECURITY;

REVOKE ALL ON TABLE "public"."fluck_oauth_nonces" FROM PUBLIC;
REVOKE ALL ON TABLE "public"."fluck_oauth_nonces" FROM "anon";
REVOKE ALL ON TABLE "public"."fluck_oauth_nonces" FROM "authenticated";
GRANT ALL ON TABLE "public"."fluck_oauth_nonces" TO "service_role";

-- Claim one nonce. TRUE means this caller got it; FALSE means the state has already been spent.
--
-- The expired sweep happens here rather than in a scheduled job so the table cannot grow without
-- bound on a deployment nobody remembered to add a cron to. It is bounded work: only rows that
-- are already past their own expiry, and the index above makes it a range scan.
CREATE OR REPLACE FUNCTION "public"."fluck_oauth_claim_nonce"(
    "p_nonce" "text",
    "p_expires_at" timestamp with time zone
) RETURNS boolean
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
AS $$
DECLARE
    v_claimed boolean;
BEGIN
    IF p_nonce IS NULL OR p_nonce = '' OR p_expires_at IS NULL
       OR p_expires_at <= now() OR p_expires_at > now() + interval '15 minutes' THEN
        RETURN false;
    END IF;

    DELETE FROM public.fluck_oauth_nonces WHERE expires_at < now();

    INSERT INTO public.fluck_oauth_nonces (nonce, expires_at)
    VALUES (p_nonce, p_expires_at)
    ON CONFLICT (nonce) DO NOTHING
    RETURNING true INTO v_claimed;

    RETURN COALESCE(v_claimed, false);
END;
$$;

ALTER FUNCTION "public"."fluck_oauth_claim_nonce"("p_nonce" "text", "p_expires_at" timestamp with time zone)
    OWNER TO "postgres";

REVOKE ALL ON FUNCTION "public"."fluck_oauth_claim_nonce"("p_nonce" "text", "p_expires_at" timestamp with time zone) FROM PUBLIC;
REVOKE ALL ON FUNCTION "public"."fluck_oauth_claim_nonce"("p_nonce" "text", "p_expires_at" timestamp with time zone) FROM "anon";
REVOKE ALL ON FUNCTION "public"."fluck_oauth_claim_nonce"("p_nonce" "text", "p_expires_at" timestamp with time zone) FROM "authenticated";
GRANT EXECUTE ON FUNCTION "public"."fluck_oauth_claim_nonce"("p_nonce" "text", "p_expires_at" timestamp with time zone) TO "service_role";

-- ---------------------------------------------------------------------------------------------
-- The secret write
-- ---------------------------------------------------------------------------------------------

-- Store a Fluck connector credential for a named BOSS user, replacing any earlier one.
--
-- ## Why this is not `create_secret`
--
-- `public.create_secret` writes as `auth.uid()`, and this caller has no user JWT: the request
-- that triggers it is an unauthenticated browser redirect, and the user it belongs to is named
-- inside a signature the edge function verified. So the user id is a PARAMETER here, which is
-- exactly why EXECUTE is revoked from everyone but `service_role` below. The whole security of
-- this function is that only the edge function, holding the service role key, can reach it.
--
-- ## Why replace rather than upsert on the index
--
-- The personal uniqueness index is (user_id, website, username) WHERE org_id IS NULL, and the
-- username here is the Google account's email. Reconnecting with a DIFFERENT Google account
-- would therefore insert a second row for the same key rather than conflicting with the first,
-- and Fluck's vault looks a secret up by `website` alone: it would find two and take whichever
-- came back first. Deleting every personal row for (user_id, website) before inserting makes the
-- key single valued, which is what the plugin's key scheme assumes. The delete cascades to
-- `secret_tags` and `secret_metadata`.
--
-- ## Why the value is encrypted here
--
-- `public.encrypt_text` is SECURITY DEFINER over a master key held in Vault. Encrypting in the
-- edge function would mean shipping that key out of the database.
CREATE OR REPLACE FUNCTION "public"."fluck_oauth_store_secret"(
    "p_user_id" "uuid",
    "p_website" "text",
    "p_username" "text",
    "p_secret" "text",
    "p_notes" "text" DEFAULT NULL
) RETURNS "uuid"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
AS $$
DECLARE
    v_secret_id uuid;
BEGIN
    IF p_user_id IS NULL THEN
        RAISE EXCEPTION 'user id is required';
    END IF;
    IF p_website IS NULL OR p_website = '' THEN
        RAISE EXCEPTION 'website is required';
    END IF;
    IF p_secret IS NULL OR p_secret = '' THEN
        RAISE EXCEPTION 'secret is required';
    END IF;

    -- The key scheme is Fluck's, and this function writes nothing else. A caller that could
    -- name any website would be able to overwrite a user's saved password for a real site.
    IF p_website NOT LIKE 'fluck/%' THEN
        RAISE EXCEPTION 'website must be a fluck connector key';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM auth.users WHERE id = p_user_id) THEN
        RAISE EXCEPTION 'unknown user';
    END IF;

    -- Serialize replacements even when no row exists yet. Two callbacks with
    -- different Google usernames must not both insert a value for the same key.
    PERFORM pg_catalog.pg_advisory_xact_lock(
        pg_catalog.hashtextextended(p_user_id::text || ':' || p_website, 0)
    );

    DELETE FROM public.secrets
    WHERE user_id = p_user_id
      AND website = p_website
      AND org_id IS NULL;

    INSERT INTO public.secrets (user_id, org_id, website, username, password_encrypted, notes)
    VALUES (
        p_user_id,
        NULL,
        p_website,
        COALESCE(NULLIF(p_username, ''), 'google account'),
        public.encrypt_text(p_secret),
        p_notes
    )
    RETURNING id INTO v_secret_id;

    -- The same two tags `HostSecretVault` writes, so a secret made here and one made by the
    -- plugin are indistinguishable in the Secret Manager list.
    INSERT INTO public.secret_tags (secret_id, tag)
    VALUES (v_secret_id, 'fluck'), (v_secret_id, 'connector');

    RETURN v_secret_id;
END;
$$;

ALTER FUNCTION "public"."fluck_oauth_store_secret"("p_user_id" "uuid", "p_website" "text", "p_username" "text", "p_secret" "text", "p_notes" "text")
    OWNER TO "postgres";

REVOKE ALL ON FUNCTION "public"."fluck_oauth_store_secret"("p_user_id" "uuid", "p_website" "text", "p_username" "text", "p_secret" "text", "p_notes" "text") FROM PUBLIC;
REVOKE ALL ON FUNCTION "public"."fluck_oauth_store_secret"("p_user_id" "uuid", "p_website" "text", "p_username" "text", "p_secret" "text", "p_notes" "text") FROM "anon";
REVOKE ALL ON FUNCTION "public"."fluck_oauth_store_secret"("p_user_id" "uuid", "p_website" "text", "p_username" "text", "p_secret" "text", "p_notes" "text") FROM "authenticated";
GRANT EXECUTE ON FUNCTION "public"."fluck_oauth_store_secret"("p_user_id" "uuid", "p_website" "text", "p_username" "text", "p_secret" "text", "p_notes" "text") TO "service_role";

COMMENT ON FUNCTION "public"."fluck_oauth_store_secret"("p_user_id" "uuid", "p_website" "text", "p_username" "text", "p_secret" "text", "p_notes" "text") IS
    'Writes a Fluck connector credential for a named user. service_role only: the caller is the fluck-oauth edge function, which has already verified a signed state naming that user.';
