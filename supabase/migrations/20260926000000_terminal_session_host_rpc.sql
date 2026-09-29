-- BossConsole plugins use authenticated RPCs; host login tokens never leave the host.
-- SECURITY INVOKER keeps terminal_sessions' existing owner-only RLS authoritative.
-- The expected identity prevents an in-flight request from writing/reading under a
-- different account if BossConsole changes session between dispatch and execution.
-- Contract: each upsert is a complete replacement of the mutable registry fields,
-- NOT a JSON merge/patch. Every heartbeat must resend its current metadata and links.
-- Required non-null fields: share_id, device_name, scope, view_url, control_url.
-- Omitted or JSON-null session_name, e2e_code and app_version clear those fields;
-- omitted or JSON-null secure writes false. Encrypted publishers must send secure=true
-- and their e2e_code on EVERY heartbeat. Explicit null is how callers clear optional
-- metadata. These replacement semantics match the already-deployed RPC and the paired
-- BossTerm publisher, which serializes a full row. user_id/timestamps are server-owned.
CREATE OR REPLACE FUNCTION public.upsert_terminal_session(p_expected_user_id uuid, p_session jsonb)
RETURNS void LANGUAGE plpgsql SECURITY INVOKER SET search_path = '' AS $$
BEGIN
    IF auth.uid() IS NULL OR auth.uid() IS DISTINCT FROM p_expected_user_id THEN
        RAISE EXCEPTION 'Terminal account changed' USING ERRCODE = '42501';
    END IF;
    INSERT INTO public.terminal_sessions (
        user_id, share_id, device_name, session_name, scope,
        view_url, control_url, secure, e2e_code, app_version
    ) VALUES (
        auth.uid(), p_session->>'share_id', p_session->>'device_name',
        p_session->>'session_name', p_session->>'scope', p_session->>'view_url',
        p_session->>'control_url', COALESCE((p_session->>'secure')::boolean, false),
        p_session->>'e2e_code', p_session->>'app_version'
    ) ON CONFLICT (user_id, share_id) DO UPDATE SET
        device_name = EXCLUDED.device_name, session_name = EXCLUDED.session_name,
        scope = EXCLUDED.scope, view_url = EXCLUDED.view_url,
        control_url = EXCLUDED.control_url, secure = EXCLUDED.secure,
        e2e_code = EXCLUDED.e2e_code, app_version = EXCLUDED.app_version;
    -- Existing trigger stamps the heartbeat and preserves started_at.
EXCEPTION WHEN data_exception OR integrity_constraint_violation THEN
    -- PostgreSQL constraint errors include the failing row in DETAIL. These rows
    -- carry account bearer links and E2E secrets; never return them in client error
    -- DETAIL. This does not redact database statement logs maintained by operators.
    RAISE EXCEPTION 'Invalid terminal session payload' USING ERRCODE = '22023';
END;
$$;

CREATE OR REPLACE FUNCTION public.delete_terminal_session(p_expected_user_id uuid, p_share_id text)
RETURNS void LANGUAGE plpgsql SECURITY INVOKER SET search_path = '' AS $$
BEGIN
    IF auth.uid() IS NULL OR auth.uid() IS DISTINCT FROM p_expected_user_id THEN
        RAISE EXCEPTION 'Terminal account changed' USING ERRCODE = '42501';
    END IF;
    DELETE FROM public.terminal_sessions WHERE user_id = auth.uid() AND share_id = p_share_id;
END;
$$;

-- Contract: this RPC is the native owner's control-session directory, not a full
-- table projection. It returns exactly share_id, device_name, session_name, scope,
-- control_url, secure, e2e_code, app_version and last_seen_at. view_url and started_at
-- are intentionally omitted: native callers open control_url; the web directory
-- still reads the owner-scoped table for read-only links and session age.
-- p_since is compatibility-only and intentionally ignored in BOTH directions:
-- callers cannot narrow or widen the fixed server-clock 90-second live window.
-- Keep this aligned with LIVE_WINDOW_SECONDS in live-sessions/utils/config.ts.
-- VOLATILE is intentional: the account RPC surface remains POST-only in PostgREST.
CREATE OR REPLACE FUNCTION public.list_terminal_sessions(p_expected_user_id uuid, p_since timestamptz)
RETURNS jsonb LANGUAGE plpgsql SECURITY INVOKER SET search_path = '' AS $$
BEGIN
    IF auth.uid() IS NULL OR auth.uid() IS DISTINCT FROM p_expected_user_id THEN
        RAISE EXCEPTION 'Terminal account changed' USING ERRCODE = '42501';
    END IF;
    RETURN COALESCE((
        SELECT jsonb_agg(to_jsonb(s) ORDER BY s.last_seen_at DESC) FROM (
            SELECT share_id, device_name, session_name, scope, control_url,
                   secure, e2e_code, app_version, last_seen_at
            FROM public.terminal_sessions
            WHERE user_id = auth.uid()
              -- Heartbeats are server-stamped, so freshness must use that same
              -- clock. Keep p_since in the RPC signature for the plugin contract,
              -- but do not let a fast client clock hide healthy sessions.
              AND last_seen_at > now() - interval '90 seconds'
            ORDER BY last_seen_at DESC LIMIT 100
        ) s
    ), '[]'::jsonb);
END;
$$;

REVOKE ALL ON FUNCTION public.upsert_terminal_session(uuid, jsonb) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.delete_terminal_session(uuid, text) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.list_terminal_sessions(uuid, timestamptz) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.upsert_terminal_session(uuid, jsonb) TO authenticated;
GRANT EXECUTE ON FUNCTION public.delete_terminal_session(uuid, text) TO authenticated;
GRANT EXECUTE ON FUNCTION public.list_terminal_sessions(uuid, timestamptz) TO authenticated;
