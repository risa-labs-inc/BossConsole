-- web.<base> "Request a BOSS" (agents contract v5 §1): people without a pod ask; platform admins approve or dismiss;
-- the provisioner sets an approved person's pod up through its per-person path and reports back.
--
-- web_pod_requests  one row per ask; at most one open (pending or approved) per person.
-- Writes only through the functions below. The provisioner bot (is_agents_provisioner) records asks for the person
-- chat vouches for, reads approved ones and reports the outcome; platform admins list, count and decide.
-- Every step is audited in platform_audit_log (actor and target only, no content).

-- platform_audit_log and platform_append_only() come from 20261021120000_pod_tiers (one definition; it runs first).

CREATE TABLE IF NOT EXISTS public.web_pod_requests (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id        uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    status         text NOT NULL DEFAULT 'pending'
                   CHECK (status IN ('pending', 'approved', 'dismissed', 'provisioned', 'failed')),
    requested_at   timestamptz NOT NULL DEFAULT now(),
    decided_by     uuid,
    decided_at     timestamptz,
    note           text CHECK (note IS NULL OR char_length(note) <= 300),
    provisioned_at timestamptz,
    detail         text CHECK (detail IS NULL OR char_length(detail) <= 120)
);
CREATE UNIQUE INDEX IF NOT EXISTS web_pod_requests_one_open ON public.web_pod_requests (user_id) WHERE status IN ('pending', 'approved');
CREATE INDEX IF NOT EXISTS web_pod_requests_status ON public.web_pod_requests (status, requested_at);
ALTER TABLE public.web_pod_requests ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.web_pod_requests FROM PUBLIC, anon, authenticated;
GRANT ALL ON public.web_pod_requests TO service_role;

-- ---- the provisioner bot ----

-- A person's ask (chat vouches for who is signed in; the bot only records it). An open ask is refreshed, not duplicated.
CREATE OR REPLACE FUNCTION public.web_pod_request_add(p_user_id uuid) RETURNS jsonb
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE r public.web_pod_requests;
BEGIN
    IF NOT public.is_agents_provisioner() THEN RAISE EXCEPTION 'Permission denied' USING ERRCODE = '42501'; END IF;
    IF NOT EXISTS (SELECT 1 FROM auth.users u WHERE u.id = p_user_id) THEN RAISE EXCEPTION 'unknown_user' USING ERRCODE = 'P0001'; END IF;
    UPDATE public.web_pod_requests SET requested_at = now()
        WHERE user_id = p_user_id AND status IN ('pending', 'approved') RETURNING * INTO r;
    IF NOT FOUND THEN
        INSERT INTO public.web_pod_requests (user_id) VALUES (p_user_id) RETURNING * INTO r;
        INSERT INTO public.platform_audit_log (actor, action, target_user, detail)
            VALUES (p_user_id, 'web_pod.request', p_user_id, jsonb_build_object('request_id', r.id));
    END IF;
    RETURN jsonb_build_object('id', r.id, 'status', r.status, 'requested_at', r.requested_at);
END;
$$;

-- Approved asks the provisioner has not acted on yet, oldest first.
CREATE OR REPLACE FUNCTION public.web_pod_requests_approved(p_limit int DEFAULT 20)
RETURNS TABLE (id uuid, user_id uuid, email text)
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
BEGIN
    IF NOT public.is_agents_provisioner() THEN RAISE EXCEPTION 'Permission denied' USING ERRCODE = '42501'; END IF;
    RETURN QUERY SELECT w.id, w.user_id, lower(u.email)::text
        FROM public.web_pod_requests w JOIN auth.users u ON u.id = w.user_id
        WHERE w.status = 'approved' ORDER BY w.decided_at, w.requested_at
        LIMIT LEAST(GREATEST(COALESCE(p_limit, 20), 1), 100);
END;
$$;

-- The outcome for one approved ask: provisioned (the pod exists) or failed (with a short code).
CREATE OR REPLACE FUNCTION public.web_pod_request_done(p_id uuid, p_ok boolean, p_detail text DEFAULT NULL) RETURNS boolean
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE r public.web_pod_requests;
BEGIN
    IF NOT public.is_agents_provisioner() THEN RAISE EXCEPTION 'Permission denied' USING ERRCODE = '42501'; END IF;
    UPDATE public.web_pod_requests
        SET status = CASE WHEN p_ok THEN 'provisioned' ELSE 'failed' END,
            provisioned_at = CASE WHEN p_ok THEN now() END,
            detail = left(p_detail, 120)
        WHERE id = p_id AND status = 'approved' RETURNING * INTO r;
    IF NOT FOUND THEN RETURN false; END IF;
    INSERT INTO public.platform_audit_log (actor, action, target_user, detail)
        VALUES (NULL, CASE WHEN p_ok THEN 'web_pod.provisioned' ELSE 'web_pod.failed' END, r.user_id,
                jsonb_build_object('request_id', r.id) || CASE WHEN p_ok THEN '{}'::jsonb ELSE jsonb_build_object('detail', left(p_detail, 120)) END);
    RETURN true;
END;
$$;

-- ---- platform admins ----

CREATE OR REPLACE FUNCTION public.platform_web_pod_requests(p_include_decided boolean DEFAULT false, p_limit int DEFAULT 200)
RETURNS TABLE (id uuid, user_id uuid, email text, status text, requested_at timestamptz, decided_at timestamptz,
               decider_email text, note text, provisioned_at timestamptz, detail text)
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
BEGIN
    IF auth.uid() IS NULL OR NOT public.is_user_admin(auth.uid()) THEN RAISE EXCEPTION 'Permission denied' USING ERRCODE = '42501'; END IF;
    RETURN QUERY SELECT w.id, w.user_id, u.email::text, w.status, w.requested_at, w.decided_at, d.email::text, w.note, w.provisioned_at, w.detail
        FROM public.web_pod_requests w
        JOIN auth.users u ON u.id = w.user_id
        LEFT JOIN auth.users d ON d.id = w.decided_by
        WHERE p_include_decided OR w.status = 'pending'
        ORDER BY (w.status = 'pending') DESC, COALESCE(w.decided_at, w.requested_at) DESC
        LIMIT LEAST(GREATEST(COALESCE(p_limit, 200), 1), 500);
END;
$$;

CREATE OR REPLACE FUNCTION public.platform_web_pod_request_count() RETURNS int
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
BEGIN
    IF auth.uid() IS NULL OR NOT public.is_user_admin(auth.uid()) THEN RAISE EXCEPTION 'Permission denied' USING ERRCODE = '42501'; END IF;
    RETURN (SELECT count(*)::int FROM public.web_pod_requests WHERE status = 'pending');
END;
$$;

-- Approve (the provisioner then sets the pod up) or dismiss a pending ask.
CREATE OR REPLACE FUNCTION public.decide_web_pod_request(p_id uuid, p_approve boolean, p_note text DEFAULT NULL) RETURNS jsonb
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE r public.web_pod_requests;
BEGIN
    IF auth.uid() IS NULL OR NOT public.is_user_admin(auth.uid()) THEN RAISE EXCEPTION 'Permission denied' USING ERRCODE = '42501'; END IF;
    IF p_note IS NOT NULL AND char_length(p_note) > 300 THEN RAISE EXCEPTION 'note_too_long' USING ERRCODE = '22023'; END IF;
    UPDATE public.web_pod_requests
        SET status = CASE WHEN p_approve THEN 'approved' ELSE 'dismissed' END,
            decided_by = auth.uid(), decided_at = now(), note = NULLIF(btrim(p_note), '')
        WHERE id = p_id AND status = 'pending' RETURNING * INTO r;
    IF NOT FOUND THEN RAISE EXCEPTION 'not_pending' USING ERRCODE = 'P0001'; END IF;
    INSERT INTO public.platform_audit_log (actor, action, target_user, detail)
        VALUES (auth.uid(), CASE WHEN p_approve THEN 'web_pod.approve' ELSE 'web_pod.dismiss' END, r.user_id,
                jsonb_build_object('request_id', r.id));
    RETURN jsonb_build_object('id', r.id, 'status', r.status);
END;
$$;

REVOKE ALL ON FUNCTION public.web_pod_request_add(uuid), public.web_pod_requests_approved(int), public.web_pod_request_done(uuid, boolean, text),
    public.platform_web_pod_requests(boolean, int), public.platform_web_pod_request_count(), public.decide_web_pod_request(uuid, boolean, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.web_pod_request_add(uuid), public.web_pod_requests_approved(int), public.web_pod_request_done(uuid, boolean, text),
    public.platform_web_pod_requests(boolean, int), public.platform_web_pod_request_count(), public.decide_web_pod_request(uuid, boolean, text) TO authenticated;
