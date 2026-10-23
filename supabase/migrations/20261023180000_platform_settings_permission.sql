-- Settings delegation is deliberately outside the extensible organisation-role graph.
-- A role named boss_admin, org owner, or plugin permission cannot mint this capability.
CREATE TABLE public.platform_permission_grants (
    user_id uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    permission text NOT NULL CHECK (permission = 'platform.settings.manage'),
    granted_by uuid REFERENCES auth.users(id) ON DELETE SET NULL DEFAULT auth.uid(),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, permission)
);
ALTER TABLE public.platform_permission_grants ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.platform_permission_grants FROM PUBLIC, anon, authenticated, service_role;
GRANT SELECT, DELETE ON public.platform_permission_grants TO authenticated;
GRANT INSERT (user_id, permission) ON public.platform_permission_grants TO authenticated;
GRANT SELECT, INSERT, DELETE ON public.platform_permission_grants TO service_role;
CREATE POLICY "platform grants self or admin read" ON public.platform_permission_grants
    FOR SELECT TO authenticated
    USING (user_id = (SELECT auth.uid()) OR (SELECT public.is_user_admin((SELECT auth.uid()))));
CREATE POLICY "platform grants admin insert" ON public.platform_permission_grants
    FOR INSERT TO authenticated
    WITH CHECK ((SELECT public.is_user_admin((SELECT auth.uid()))));
CREATE POLICY "platform grants admin delete" ON public.platform_permission_grants
    FOR DELETE TO authenticated
    USING ((SELECT public.is_user_admin((SELECT auth.uid()))));

CREATE FUNCTION public.platform_can_manage_settings() RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
    SELECT auth.uid() IS NOT NULL AND (
        public.is_user_admin(auth.uid()) OR EXISTS (
            SELECT 1 FROM public.platform_permission_grants g
            WHERE g.user_id = auth.uid() AND g.permission = 'platform.settings.manage'
        )
    );
$$;
REVOKE ALL ON FUNCTION public.platform_can_manage_settings() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.platform_can_manage_settings() TO authenticated, service_role;

-- Caller-only contract: no user-id argument and no role/permission enumeration.
CREATE FUNCTION public.platform_capabilities() RETURNS jsonb
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
    SELECT pg_catalog.jsonb_build_object(
        'admin', auth.uid() IS NOT NULL AND public.is_user_admin(auth.uid()),
        'settings_manage', public.platform_can_manage_settings()
    );
$$;
REVOKE ALL ON FUNCTION public.platform_capabilities() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.platform_capabilities() TO authenticated, service_role;

CREATE FUNCTION public.platform_permission_grants_audit() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
BEGIN
    INSERT INTO public.platform_audit_log (actor, action, target_user, detail)
    VALUES (auth.uid(), CASE WHEN TG_OP = 'INSERT' THEN 'platform.permission_granted' ELSE 'platform.permission_revoked' END,
        CASE WHEN TG_OP = 'INSERT' THEN NEW.user_id ELSE OLD.user_id END,
        pg_catalog.jsonb_build_object('permission', CASE WHEN TG_OP = 'INSERT' THEN NEW.permission ELSE OLD.permission END));
    RETURN NULL;
END;
$$;
REVOKE ALL ON FUNCTION public.platform_permission_grants_audit() FROM PUBLIC, anon, authenticated, service_role;
CREATE TRIGGER platform_permission_grants_audit AFTER INSERT OR DELETE ON public.platform_permission_grants
    FOR EACH ROW EXECUTE FUNCTION public.platform_permission_grants_audit();

-- Preserve existing full-admin behavior. Delegates may update only existing UI boolean values.
-- The existing UPDATE(value)-only column privilege also prevents changing a flag's key.
DROP POLICY "platform_flags admin update" ON public.platform_flags;
CREATE POLICY "platform_flags admin update" ON public.platform_flags FOR UPDATE TO authenticated
    USING (
        (SELECT public.is_user_admin((SELECT auth.uid()))) OR (
            (SELECT public.platform_can_manage_settings())
            AND key IN ('agent_creation_open', 'agent_update_review', 'org_creation_open', 'web_pod_self_serve')
        )
    )
    WITH CHECK (
        (SELECT public.is_user_admin((SELECT auth.uid()))) OR (
            (SELECT public.platform_can_manage_settings())
            AND key IN ('agent_creation_open', 'agent_update_review', 'org_creation_open', 'web_pod_self_serve')
            AND pg_catalog.jsonb_typeof(value) = 'boolean'
        )
    );

-- Keep the history signature/paging and existing flag audit trigger unchanged.
-- Delegates cannot read platform review, tier, grant, domain, or arbitrary flag audit data.
CREATE OR REPLACE FUNCTION public.platform_settings_history(p_limit int DEFAULT 20, p_before timestamptz DEFAULT NULL)
RETURNS TABLE (at timestamptz, actor_email text, key text, from_value jsonb, to_value jsonb)
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
#variable_conflict use_column
DECLARE
    v_admin boolean := auth.uid() IS NOT NULL AND public.is_user_admin(auth.uid());
BEGIN
    IF NOT public.platform_can_manage_settings() THEN
        RAISE EXCEPTION 'Permission denied' USING ERRCODE = '42501';
    END IF;
    RETURN QUERY
        SELECT l.at, u.email::text, l.detail->>'key', l.detail->'from', l.detail->'to'
        FROM public.platform_audit_log l LEFT JOIN auth.users u ON u.id = l.actor
        WHERE l.action = 'platform.flag_set' AND (p_before IS NULL OR l.at < p_before)
          AND (v_admin OR l.detail->>'key' IN (
              'agent_creation_open', 'agent_update_review', 'org_creation_open', 'web_pod_self_serve'))
        ORDER BY l.at DESC, l.id DESC
        LIMIT LEAST(GREATEST(COALESCE(p_limit, 20), 1), 100);
END;
$$;
REVOKE ALL ON FUNCTION public.platform_settings_history(int, timestamptz) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.platform_settings_history(int, timestamptz) TO authenticated, service_role;
