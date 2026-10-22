-- web.<base> polish: the person's latest "Request a BOSS" for the provisioner bot, so the web page can say a dismissal
-- (with the admin's note) and offer to ask again. Asking again after a dismissal already opens a new ask
-- (web_pod_request_add only refreshes an open one); an open ask, when there is one, is the latest.

CREATE OR REPLACE FUNCTION public.web_pod_request_latest(p_user_id uuid) RETURNS jsonb
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
DECLARE r public.web_pod_requests;
BEGIN
    IF NOT public.is_agents_provisioner() THEN RAISE EXCEPTION 'Permission denied' USING ERRCODE = '42501'; END IF;
    SELECT * INTO r FROM public.web_pod_requests w WHERE w.user_id = p_user_id ORDER BY (w.status IN ('pending', 'approved')) DESC, w.requested_at DESC, w.id LIMIT 1;
    IF NOT FOUND THEN RETURN NULL; END IF;
    RETURN jsonb_build_object('id', r.id, 'status', r.status, 'requested_at', r.requested_at, 'decided_at', r.decided_at,
        'note', CASE WHEN r.status = 'dismissed' THEN r.note END);
END;
$$;

REVOKE ALL ON FUNCTION public.web_pod_request_latest(uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.web_pod_request_latest(uuid) TO authenticated;
