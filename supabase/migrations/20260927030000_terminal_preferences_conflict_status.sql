-- Compare-and-set conflicts are a client revision conflict, not a retryable transaction failure.
-- PostgREST maps SQLSTATE 40xxx to HTTP 500; PT409 explicitly returns HTTP 409.
-- CREATE OR REPLACE preserves the existing signature, ownership and EXECUTE grants.
CREATE OR REPLACE FUNCTION public.set_user_terminal_preferences(
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
    IF changed IS NULL THEN RAISE EXCEPTION 'Preferences changed; reload and try again' USING ERRCODE = 'PT409'; END IF;
    RETURN public.get_user_terminal_preferences(p_actor_id);
END;
$$;
