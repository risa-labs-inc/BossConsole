-- Optimist chats get their own role. A platform viewer reads people, pods and the rest of the console but no
-- longer people's Optimist conversations or tool calls; those need platform_chat_viewer (or platform admin,
-- which always has everything). The role is an add-on: the console itself still needs admin or viewer.

INSERT INTO public.roles (name, description, is_system) VALUES
    ('platform_chat_viewer', 'Reads people''s Optimist conversations and tool calls in the agents platform console (every read is audited). Add-on to platform_viewer.', true)
ON CONFLICT (name) DO NOTHING;
UPDATE public.roles SET description = 'Reads the agents platform console (agents, people, pods, requests, settings) without taking any action. People''s chats need platform_chat_viewer.'
 WHERE name = 'platform_viewer';

INSERT INTO public.role_hierarchy (parent_role_id, child_role_id)
SELECT parent.id, child.id FROM public.roles parent, public.roles child
 WHERE parent.name = 'admin' AND child.name = 'platform_chat_viewer'
ON CONFLICT DO NOTHING;

CREATE OR REPLACE FUNCTION public.is_platform_chat_reader(p_user_id uuid) RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
    SELECT p_user_id IS NOT NULL AND (public.is_user_admin(p_user_id) OR EXISTS (
        SELECT 1 FROM public.user_roles ur JOIN public.roles r ON r.id = ur.role_id
         WHERE ur.user_id = p_user_id AND r.name = 'platform_chat_viewer'));
$$;
REVOKE ALL ON FUNCTION public.is_platform_chat_reader(uuid) FROM PUBLIC, anon, authenticated;

-- Whether the caller may read people's Optimist chats (the console shows or hides the viewer).
CREATE OR REPLACE FUNCTION public.platform_chat_access() RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
    SELECT public.is_platform_chat_reader(auth.uid());
$$;
REVOKE ALL ON FUNCTION public.platform_chat_access() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.platform_chat_access() TO authenticated;

-- Grants or revokes chat access. Admins only; audited. Returns whether they now have it.
CREATE OR REPLACE FUNCTION public.platform_set_chat_viewer(p_user_id uuid, p_grant boolean, p_note text DEFAULT NULL)
RETURNS boolean
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    v_actor uuid := public.require_platform_admin();
    v_role uuid;
    v_changed int;
BEGIN
    IF p_user_id IS NULL OR p_grant IS NULL THEN
        RAISE EXCEPTION 'bad_input' USING ERRCODE = '22023';
    END IF;
    IF pg_catalog.char_length(COALESCE(p_note, '')) > 500 THEN
        RAISE EXCEPTION 'note_too_long' USING ERRCODE = '22023';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM auth.users u WHERE u.id = p_user_id AND u.deleted_at IS NULL) THEN
        RAISE EXCEPTION 'user_not_found' USING ERRCODE = 'P0002';
    END IF;
    SELECT r.id INTO v_role FROM public.roles r WHERE r.name = 'platform_chat_viewer';
    IF p_grant THEN
        INSERT INTO public.user_roles (user_id, role_id, assigned_by) VALUES (p_user_id, v_role, v_actor)
            ON CONFLICT (user_id, role_id) DO NOTHING;
    ELSE
        DELETE FROM public.user_roles ur WHERE ur.user_id = p_user_id AND ur.role_id = v_role;
    END IF;
    GET DIAGNOSTICS v_changed = ROW_COUNT;
    IF v_changed > 0 THEN
        INSERT INTO public.platform_audit_log (actor, action, target_user, detail)
            VALUES (v_actor, CASE WHEN p_grant THEN 'platform_role.chat_grant' ELSE 'platform_role.chat_revoke' END, p_user_id,
                    pg_catalog.jsonb_build_object('note', NULLIF(pg_catalog.btrim(COALESCE(p_note, '')), '')));
    END IF;
    RETURN public.is_platform_chat_reader(p_user_id);
END;
$$;
REVOKE ALL ON FUNCTION public.platform_set_chat_viewer(uuid, boolean, text) FROM PUBLIC, anon, service_role;
GRANT EXECUTE ON FUNCTION public.platform_set_chat_viewer(uuid, boolean, text) TO authenticated;

-- platform_record_access: body from 20261028120000_platform_viewer_role.sql; Optimist chats now need the chat role.
-- chat/server records before it reads anything, so this is the gate: a refusal here means nothing is read.
CREATE OR REPLACE FUNCTION public.platform_record_access(p_target_email text, p_what text)
RETURNS void
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    v_actor uuid := public.require_platform_reader();
    v_email text := pg_catalog.lower(pg_catalog.btrim(COALESCE(p_target_email, '')));
BEGIN
    IF p_what IS NULL OR p_what NOT IN ('optimist.conversation', 'optimist.activity') THEN
        RAISE EXCEPTION 'bad_what' USING ERRCODE = '22023';
    END IF;
    IF NOT public.is_platform_chat_reader(v_actor) THEN
        RAISE EXCEPTION 'no_chat_access' USING ERRCODE = '42501';
    END IF;
    IF v_email = '' OR pg_catalog.char_length(v_email) > 320 THEN
        RAISE EXCEPTION 'bad_email' USING ERRCODE = '22023';
    END IF;
    INSERT INTO public.platform_audit_log (actor, action, target_user, detail)
        VALUES (v_actor, 'view.' || p_what,
                (SELECT u.id FROM auth.users u WHERE pg_catalog.lower(u.email::text) = v_email LIMIT 1),
                pg_catalog.jsonb_build_object('email', v_email, 'role', public.platform_role_of(v_actor)));
END;
$$;
REVOKE ALL ON FUNCTION public.platform_record_access(text, text) FROM PUBLIC, anon, service_role;
GRANT EXECUTE ON FUNCTION public.platform_record_access(text, text) TO authenticated;

-- platform_people_page: body from 20261028120000_platform_viewer_role.sql, plus each person's chat_access.
CREATE OR REPLACE FUNCTION public.platform_people_page(p_after uuid DEFAULT NULL, p_limit int DEFAULT 100, p_query text DEFAULT NULL)
RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    v_q text := pg_catalog.lower(NULLIF(pg_catalog.btrim(COALESCE(p_query, '')), ''));
    v_limit int := LEAST(GREATEST(COALESCE(p_limit, 100), 1), 200);
    result jsonb;
BEGIN
    PERFORM public.require_platform_reader();
    IF pg_catalog.char_length(COALESCE(v_q, '')) > 320 THEN
        RAISE EXCEPTION 'query_too_long' USING ERRCODE = '22023';
    END IF;
        WITH people AS (
        SELECT u.id, u.email::text AS email
          FROM auth.users u
         WHERE NOT EXISTS (SELECT 1 FROM public.agents_service_accounts s WHERE s.user_id = u.id)
           AND u.email IS NOT NULL
           AND u.deleted_at IS NULL
           AND (p_after IS NULL OR u.id > p_after)
           AND (v_q IS NULL OR pg_catalog.strpos(pg_catalog.lower(u.email::text),v_q)>0)
         ORDER BY u.id LIMIT v_limit+1
    ), inst AS (
        SELECT i.user_id,
               pg_catalog.jsonb_agg(pg_catalog.jsonb_build_object(
                   'agent_id', a.id, 'slug', a.slug, 'name', a.name, 'state', i.state, 'instance_id', i.instance_id,
                   'last_seen_at', i.last_seen_at, 'running_version', i.running_version) ORDER BY a.slug) AS installs,
               pg_catalog.max(i.last_seen_at) AS last_seen_at
          FROM public.agent_installs i JOIN public.agents a ON a.id = i.agent_id
         WHERE i.state <> 'removed' AND i.user_id IN (SELECT p.id FROM people p)
         GROUP BY i.user_id
    )
    , page AS (
    SELECT p.id AS user_id, p.email, t.tier, t.note AS tier_note, t.assigned_at AS tier_assigned_at,
           ab.email::text AS tier_assigned_by_email, inst.last_seen_at,
           COALESCE(inst.installs, '[]'::jsonb) AS installs,
           public.platform_role_of(p.id) AS platform_role,
           public.is_platform_chat_reader(p.id) AS chat_access
      FROM people p
      LEFT JOIN public.pod_tiers t ON t.user_id = p.id
      LEFT JOIN auth.users ab ON ab.id = t.assigned_by
      LEFT JOIN inst ON inst.user_id = p.id
     ORDER BY p.id LIMIT v_limit
    )
    SELECT pg_catalog.jsonb_build_object('people',COALESCE((SELECT pg_catalog.jsonb_agg(pg_catalog.to_jsonb(p) ORDER BY p.user_id) FROM page p),'[]'::jsonb),
      'next_cursor',CASE WHEN (SELECT count(*) FROM people)>v_limit THEN (SELECT user_id FROM page ORDER BY user_id DESC LIMIT 1) ELSE NULL END)
      INTO result;
    RETURN result;
END;
$$;
