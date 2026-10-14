-- fluck_oauth_bind_grant: a token hash bound to one user is never re-bound to another.
--
-- The old upsert moved ownership on conflict, which was safe only while every exchange minted a
-- fresh refresh token. Now a same-user repeat stays idempotent (TRUE) and a cross-user bind is
-- FALSE, which the callback turns into a 503 that writes no secret.

CREATE OR REPLACE FUNCTION "public"."fluck_oauth_bind_grant"(
    "p_token_sha256" "text",
    "p_user_id" "uuid"
) RETURNS boolean
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
AS $$
DECLARE
    v_bound boolean;
BEGIN
    IF p_user_id IS NULL OR p_token_sha256 IS NULL OR p_token_sha256 !~ '^[0-9a-f]{64}$' THEN
        RETURN false;
    END IF;
    INSERT INTO public.fluck_oauth_grants AS g (token_sha256, user_id)
    VALUES (p_token_sha256, p_user_id)
    ON CONFLICT (token_sha256) DO UPDATE SET user_id = EXCLUDED.user_id
        WHERE g.user_id = EXCLUDED.user_id
    RETURNING true INTO v_bound;
    RETURN COALESCE(v_bound, false);
END;
$$;

ALTER FUNCTION "public"."fluck_oauth_bind_grant"("p_token_sha256" "text", "p_user_id" "uuid")
    OWNER TO "postgres";

REVOKE ALL ON FUNCTION "public"."fluck_oauth_bind_grant"("p_token_sha256" "text", "p_user_id" "uuid") FROM PUBLIC;
REVOKE ALL ON FUNCTION "public"."fluck_oauth_bind_grant"("p_token_sha256" "text", "p_user_id" "uuid") FROM "anon";
REVOKE ALL ON FUNCTION "public"."fluck_oauth_bind_grant"("p_token_sha256" "text", "p_user_id" "uuid") FROM "authenticated";
GRANT EXECUTE ON FUNCTION "public"."fluck_oauth_bind_grant"("p_token_sha256" "text", "p_user_id" "uuid") TO "service_role";
