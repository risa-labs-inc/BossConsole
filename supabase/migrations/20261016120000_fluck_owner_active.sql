-- When each Fluck's owner last used it, so an agent asking a person reaches the agent that person is using now.
-- Visible only where the peer list already shows the instance: the owner's own agents and accepted connections.

ALTER TABLE public.fluck_web_instances ADD COLUMN IF NOT EXISTS owner_active_at timestamptz;

-- Called by a Fluck when its owner writes to it (at most about once a minute). Only the instance's own user.
CREATE OR REPLACE FUNCTION public.fluck_web_owner_active(p_instance_id text)
 RETURNS void
 LANGUAGE sql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
    UPDATE public.fluck_web_instances
       SET owner_active_at = now()
     WHERE instance_id = p_instance_id
       AND user_id = auth.uid();
$function$;

REVOKE ALL ON FUNCTION public.fluck_web_owner_active(text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.fluck_web_owner_active(text) TO authenticated, service_role;

-- The peer list gains owner_active_at (a changed result type needs the function recreated).
DROP FUNCTION IF EXISTS public.fluck_peer_list();
CREATE FUNCTION public.fluck_peer_list()
 RETURNS TABLE(user_id uuid, email text, same_user boolean, instance_id text, label text, agent_name text, endpoint_url text, online boolean, owner_active_at timestamptz)
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
#variable_conflict use_column
DECLARE
    actor uuid := auth.uid();
BEGIN
    IF actor IS NULL THEN
        RAISE EXCEPTION 'Not signed in' USING ERRCODE = '42501';
    END IF;
    RETURN QUERY
        SELECT i.user_id, u.email::text, (i.user_id = actor), i.instance_id, i.label,
               i.agent_name, i.endpoint_url, (i.last_seen_at > now() - interval '90 seconds'), i.owner_active_at
        FROM public.fluck_web_instances i
        JOIN auth.users u ON u.id = i.user_id
        WHERE i.user_id = actor
           OR EXISTS (
                SELECT 1 FROM public.fluck_peer_links l
                WHERE l.status = 'accepted'
                  AND ((l.requester_id = actor AND l.recipient_id = i.user_id)
                    OR (l.recipient_id = actor AND l.requester_id = i.user_id))
           )
        ORDER BY (i.user_id = actor) DESC, i.last_seen_at DESC
        LIMIT 200;
END;
$function$;

REVOKE ALL ON FUNCTION public.fluck_peer_list() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.fluck_peer_list() TO authenticated;
