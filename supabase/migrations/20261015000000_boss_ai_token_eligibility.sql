-- /auth/token must not mint an AI token for users the /auth/exchange path
-- would refuse. The exchange path returns NULL for banned, anonymous, and
-- ai.use-less users via boss_ai_consume_exchange_ticket; the direct path
-- previously only checked that the session was valid, so a banned user
-- holding an AI token could probe /v1/models and /v1/usage (the result was
-- the same 403 downstream, but the token-mint itself was a distinguishable
-- signal a careful probe could use). One RPC, same predicate, used by both
-- endpoints.
BEGIN;
CREATE FUNCTION public.boss_ai_token_eligible(p_user_id uuid)
RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
  SELECT
    p_user_id IS NOT NULL
    AND EXISTS (
      SELECT 1 FROM auth.users u
      WHERE u.id = p_user_id
        AND NOT COALESCE(u.is_anonymous, false)
        AND (u.banned_until IS NULL OR u.banned_until < now())
    )
    AND public.user_has_permission(p_user_id, 'ai.use') IS TRUE;
$$;
REVOKE ALL ON FUNCTION public.boss_ai_token_eligible(uuid) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.boss_ai_token_eligible(uuid) TO service_role;
COMMIT;
