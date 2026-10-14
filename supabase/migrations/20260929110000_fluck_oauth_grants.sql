-- Fluck Google connector: bind each refresh token to the BOSS user it was issued to.
--
-- `POST /refresh` on fluck-oauth redeems a refresh token with the web client secret for any
-- registered, unrevoked install. Without a binding, a stolen refresh token becomes usable by
-- anyone who signs in to BOSS and registers an install, where before it was useless without our
-- client secret. The callback now records which user a token was issued to, and `/refresh`
-- refuses any token not bound to the calling install's owner.
--
-- Only the SHA-256 of the token is stored, never the token. Grants made before this migration
-- have no row, so `/refresh` answers them `invalid_grant` and the plugin asks the owner to
-- reconnect once.
--
-- Apply before deploying the fluck-oauth version that calls these functions.

-- ---------------------------------------------------------------------------------------------
-- The bindings
-- ---------------------------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS "public"."fluck_oauth_grants" (
    -- Lowercase hex SHA-256 of the refresh token.
    "token_sha256" "text" NOT NULL,
    "user_id" "uuid" NOT NULL,
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    CONSTRAINT "fluck_oauth_grants_pkey" PRIMARY KEY ("token_sha256"),
    CONSTRAINT "fluck_oauth_grants_user_id_fkey"
        FOREIGN KEY ("user_id") REFERENCES "auth"."users"("id") ON DELETE CASCADE,
    CONSTRAINT "fluck_oauth_grants_token_sha256_check"
        CHECK ("token_sha256" ~ '^[0-9a-f]{64}$')
);

ALTER TABLE "public"."fluck_oauth_grants" OWNER TO "postgres";

COMMENT ON TABLE "public"."fluck_oauth_grants" IS
    'One row per Google refresh token fluck-oauth issued: its SHA-256 and the BOSS user it belongs to. Never the token. Written through fluck_oauth_bind_grant; service_role only.';

CREATE INDEX IF NOT EXISTS "idx_fluck_oauth_grants_user_id"
    ON "public"."fluck_oauth_grants" ("user_id");

-- RLS on with no policies: reachable by service_role and the definer functions below only.
ALTER TABLE "public"."fluck_oauth_grants" ENABLE ROW LEVEL SECURITY;

REVOKE ALL ON TABLE "public"."fluck_oauth_grants" FROM PUBLIC;
REVOKE ALL ON TABLE "public"."fluck_oauth_grants" FROM "anon";
REVOKE ALL ON TABLE "public"."fluck_oauth_grants" FROM "authenticated";
GRANT ALL ON TABLE "public"."fluck_oauth_grants" TO "service_role";

-- ---------------------------------------------------------------------------------------------
-- Bind, look up, forget
-- ---------------------------------------------------------------------------------------------

-- Record that a token belongs to a user. TRUE on success, FALSE on invalid input.
--
-- An upsert: Google mints a new refresh token per exchange, so a repeat is the same callback
-- retried, not a second owner.
CREATE OR REPLACE FUNCTION "public"."fluck_oauth_bind_grant"(
    "p_token_sha256" "text",
    "p_user_id" "uuid"
) RETURNS boolean
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
AS $$
BEGIN
    IF p_user_id IS NULL OR p_token_sha256 IS NULL OR p_token_sha256 !~ '^[0-9a-f]{64}$' THEN
        RETURN false;
    END IF;
    INSERT INTO public.fluck_oauth_grants (token_sha256, user_id)
    VALUES (p_token_sha256, p_user_id)
    ON CONFLICT (token_sha256) DO UPDATE SET user_id = EXCLUDED.user_id;
    RETURN true;
END;
$$;

ALTER FUNCTION "public"."fluck_oauth_bind_grant"("p_token_sha256" "text", "p_user_id" "uuid")
    OWNER TO "postgres";

REVOKE ALL ON FUNCTION "public"."fluck_oauth_bind_grant"("p_token_sha256" "text", "p_user_id" "uuid") FROM PUBLIC;
REVOKE ALL ON FUNCTION "public"."fluck_oauth_bind_grant"("p_token_sha256" "text", "p_user_id" "uuid") FROM "anon";
REVOKE ALL ON FUNCTION "public"."fluck_oauth_bind_grant"("p_token_sha256" "text", "p_user_id" "uuid") FROM "authenticated";
GRANT EXECUTE ON FUNCTION "public"."fluck_oauth_bind_grant"("p_token_sha256" "text", "p_user_id" "uuid") TO "service_role";

-- The user a token is bound to, or NULL if it is unbound.
CREATE OR REPLACE FUNCTION "public"."fluck_oauth_grant_owner"("p_token_sha256" "text")
RETURNS "uuid"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
AS $$
BEGIN
    RETURN (SELECT g.user_id FROM public.fluck_oauth_grants g
            WHERE g.token_sha256 = p_token_sha256);
END;
$$;

ALTER FUNCTION "public"."fluck_oauth_grant_owner"("p_token_sha256" "text") OWNER TO "postgres";

REVOKE ALL ON FUNCTION "public"."fluck_oauth_grant_owner"("p_token_sha256" "text") FROM PUBLIC;
REVOKE ALL ON FUNCTION "public"."fluck_oauth_grant_owner"("p_token_sha256" "text") FROM "anon";
REVOKE ALL ON FUNCTION "public"."fluck_oauth_grant_owner"("p_token_sha256" "text") FROM "authenticated";
GRANT EXECUTE ON FUNCTION "public"."fluck_oauth_grant_owner"("p_token_sha256" "text") TO "service_role";

-- Drop a binding once Google says the grant is dead.
CREATE OR REPLACE FUNCTION "public"."fluck_oauth_forget_grant"("p_token_sha256" "text")
RETURNS void
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
AS $$
BEGIN
    DELETE FROM public.fluck_oauth_grants WHERE token_sha256 = p_token_sha256;
END;
$$;

ALTER FUNCTION "public"."fluck_oauth_forget_grant"("p_token_sha256" "text") OWNER TO "postgres";

REVOKE ALL ON FUNCTION "public"."fluck_oauth_forget_grant"("p_token_sha256" "text") FROM PUBLIC;
REVOKE ALL ON FUNCTION "public"."fluck_oauth_forget_grant"("p_token_sha256" "text") FROM "anon";
REVOKE ALL ON FUNCTION "public"."fluck_oauth_forget_grant"("p_token_sha256" "text") FROM "authenticated";
GRANT EXECUTE ON FUNCTION "public"."fluck_oauth_forget_grant"("p_token_sha256" "text") TO "service_role";
