-- Allow at most one minute of database/edge clock skew without making a spent
-- nonce reusable while the edge still considers its signed state valid.
-- Keep the boolean RPC contract: false means replay, invalid input raises 22023.
BEGIN;
CREATE OR REPLACE FUNCTION public.fluck_oauth_claim_nonce(
    p_nonce text,
    p_expires_at timestamptz
) RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    v_claimed boolean;
BEGIN
    IF p_nonce IS NULL OR p_nonce = '' OR length(p_nonce) > 256
       OR p_expires_at IS NULL
       OR p_expires_at <= now() - interval '1 minute'
       OR p_expires_at > now() + interval '16 minutes' THEN
        RAISE EXCEPTION 'invalid OAuth nonce or expiry' USING ERRCODE = '22023';
    END IF;

    DELETE FROM public.fluck_oauth_nonces
    WHERE expires_at < now() - interval '1 minute';

    INSERT INTO public.fluck_oauth_nonces (nonce, expires_at)
    VALUES (p_nonce, p_expires_at)
    ON CONFLICT (nonce) DO NOTHING
    RETURNING true INTO v_claimed;
    RETURN COALESCE(v_claimed, false);
END;
$$;
REVOKE ALL ON FUNCTION public.fluck_oauth_claim_nonce(text,timestamptz) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.fluck_oauth_claim_nonce(text,timestamptz) TO service_role;
COMMIT;
