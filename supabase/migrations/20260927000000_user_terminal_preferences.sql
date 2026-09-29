-- Account-owned remote terminal preferences and single-use web handoffs.
CREATE TABLE public.user_terminal_preferences (
    user_id uuid PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
    unfocused_mode text NOT NULL DEFAULT 'batch' CHECK (unfocused_mode IN ('batch', 'preview')),
    unfocused_fps integer NOT NULL DEFAULT 4 CHECK (unfocused_fps BETWEEN 1 AND 30),
    revision bigint NOT NULL DEFAULT 1,
    updated_at timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE public.user_terminal_preferences ENABLE ROW LEVEL SECURITY;
CREATE POLICY "owner reads terminal preferences" ON public.user_terminal_preferences
    FOR SELECT TO authenticated USING (user_id = (SELECT auth.uid()));
REVOKE ALL ON public.user_terminal_preferences FROM PUBLIC, anon, authenticated;
GRANT SELECT ON public.user_terminal_preferences TO authenticated;

-- Impersonation is available only to trusted Edge Functions, never to clients.
CREATE FUNCTION public.terminal_settings_actor(p_actor_id uuid DEFAULT NULL)
RETURNS uuid LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE actor uuid;
BEGIN
    IF p_actor_id IS NOT NULL THEN
        IF auth.role() IS DISTINCT FROM 'service_role' THEN
            RAISE EXCEPTION 'Not authorized' USING ERRCODE = '42501';
        END IF;
        actor := p_actor_id;
    ELSE actor := auth.uid();
    END IF;
    IF actor IS NULL OR NOT EXISTS (SELECT 1 FROM auth.users WHERE id = actor) THEN
        RAISE EXCEPTION 'Not authenticated' USING ERRCODE = '42501';
    END IF;
    RETURN actor;
END;
$$;
REVOKE ALL ON FUNCTION public.terminal_settings_actor(uuid) FROM PUBLIC, anon, authenticated;

CREATE FUNCTION public.get_user_terminal_preferences(p_actor_id uuid DEFAULT NULL, p_expected_user_id uuid DEFAULT NULL)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE actor uuid := public.terminal_settings_actor(p_actor_id); result jsonb;
BEGIN
    IF p_expected_user_id IS NOT NULL AND actor IS DISTINCT FROM p_expected_user_id THEN
        RAISE EXCEPTION 'Account changed' USING ERRCODE = '42501';
    END IF;
    SELECT jsonb_build_object('unfocused_mode', unfocused_mode, 'unfocused_fps', unfocused_fps,
                             'revision', revision) INTO result
    FROM public.user_terminal_preferences WHERE user_id = actor;
    RETURN COALESCE(result, '{"unfocused_mode":"batch","unfocused_fps":4,"revision":0}'::jsonb);
END;
$$;
CREATE FUNCTION public.set_user_terminal_preferences(
    p_unfocused_mode text, p_unfocused_fps integer, p_revision bigint, p_actor_id uuid DEFAULT NULL
) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE actor uuid := public.terminal_settings_actor(p_actor_id); changed uuid;
BEGIN
    IF p_unfocused_mode IS NULL OR p_unfocused_mode NOT IN ('batch', 'preview') OR
       p_unfocused_fps IS NULL OR p_unfocused_fps NOT BETWEEN 1 AND 30 OR
       p_revision IS NULL OR p_revision < 0 THEN
        RAISE EXCEPTION 'Invalid preferences' USING ERRCODE = '22023';
    END IF;
    PERFORM pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(actor::text, 17));
    IF p_revision = 0 THEN
        INSERT INTO public.user_terminal_preferences(user_id, unfocused_mode, unfocused_fps)
        VALUES(actor, p_unfocused_mode, p_unfocused_fps)
        ON CONFLICT DO NOTHING RETURNING user_id INTO changed;
    ELSE
        UPDATE public.user_terminal_preferences SET unfocused_mode = p_unfocused_mode,
            unfocused_fps = p_unfocused_fps, revision = revision + 1, updated_at = now()
        WHERE user_id = actor AND revision = p_revision RETURNING user_id INTO changed;
    END IF;
    IF changed IS NULL THEN RAISE EXCEPTION 'Preferences changed; reload and try again' USING ERRCODE = '40001'; END IF;
    RETURN public.get_user_terminal_preferences(p_actor_id);
END;
$$;
REVOKE ALL ON FUNCTION public.get_user_terminal_preferences(uuid, uuid) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.set_user_terminal_preferences(text, integer, bigint, uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.get_user_terminal_preferences(uuid, uuid) TO authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.set_user_terminal_preferences(text, integer, bigint, uuid) TO authenticated, service_role;

CREATE TABLE public.user_settings_handoffs (
    token_hash text PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    expires_at timestamptz NOT NULL
);
ALTER TABLE public.user_settings_handoffs ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.user_settings_handoffs FROM PUBLIC, anon, authenticated;
CREATE INDEX user_settings_handoffs_user_expiry ON public.user_settings_handoffs(user_id, expires_at);
CREATE INDEX user_settings_handoffs_expiry ON public.user_settings_handoffs(expires_at);

CREATE FUNCTION public.mint_user_settings_handoff(p_expected_user_id uuid DEFAULT NULL)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE actor uuid := public.terminal_settings_actor(); token text;
BEGIN
    IF p_expected_user_id IS NOT NULL AND actor IS DISTINCT FROM p_expected_user_id THEN
        RAISE EXCEPTION 'Account changed' USING ERRCODE = '42501';
    END IF;
    PERFORM pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(actor::text, 18));
    -- An account action must not scan/delete every other account's expired handoffs.
    DELETE FROM public.user_settings_handoffs WHERE user_id = actor AND expires_at <= now();
    IF (SELECT count(*) FROM public.user_settings_handoffs WHERE user_id = actor) >= 10 THEN
        RAISE EXCEPTION 'Too many pending settings pages' USING ERRCODE = '54000';
    END IF;
    token := translate(encode(extensions.gen_random_bytes(32), 'base64'), '+/=', '-_');
    INSERT INTO public.user_settings_handoffs VALUES
        (encode(extensions.digest(token, 'sha256'), 'hex'), actor, now() + interval '5 minutes');
    RETURN jsonb_build_object('token', token);
END;
$$;
CREATE FUNCTION public.consume_user_settings_handoff(p_token text)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE actor uuid;
BEGIN
    DELETE FROM public.user_settings_handoffs
    WHERE token_hash = encode(extensions.digest(p_token, 'sha256'), 'hex') AND expires_at > now()
    RETURNING user_id INTO actor;
    IF actor IS NULL THEN RAISE EXCEPTION 'Invalid handoff' USING ERRCODE = '42501'; END IF;
    RETURN jsonb_build_object('user_id', actor);
END;
$$;
REVOKE ALL ON FUNCTION public.mint_user_settings_handoff(uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.mint_user_settings_handoff(uuid) TO authenticated;
REVOKE ALL ON FUNCTION public.consume_user_settings_handoff(text) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.consume_user_settings_handoff(text) TO service_role;
