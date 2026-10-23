-- QA walkthrough fixes (agents platform):
--   1. Org default brand: saved through set_org_default_brand (the client upsert needed UPDATE on org_id).
--   2. New agents start with the org's default accent and a closed sign-up policy (verified domain, else invite).
--   3. Email-domain helpers for the console: who claimed a domain; an org's own domains.
--   4. Audit rows keep the agent's name after it is suspended or deleted.
--   5. Platform settings history pages with p_before.
--   6. Suspending an agent and rejecting one in review need a reason.

-- ----------------------------------------------------------------------------
-- 1. Org default brand
-- ----------------------------------------------------------------------------
-- Invoker: RLS ("agent_org_defaults edit insert/update", agents.edit) and the check/audit triggers still apply.
CREATE OR REPLACE FUNCTION public.set_org_default_brand(p_org_id uuid, p_brand jsonb)
RETURNS void LANGUAGE plpgsql SECURITY INVOKER SET search_path = '' AS $$
BEGIN
    IF auth.uid() IS NULL OR NOT public.has_org_permission(p_org_id, 'agents.edit') THEN
        RAISE EXCEPTION 'Permission denied: agents.edit' USING ERRCODE = '42501';
    END IF;
    INSERT INTO public.agent_org_defaults (org_id, default_brand)
        VALUES (p_org_id, COALESCE(p_brand, '{}'::jsonb))
        ON CONFLICT (org_id) DO UPDATE SET default_brand = EXCLUDED.default_brand;
END;
$$;
REVOKE ALL ON FUNCTION public.set_org_default_brand(uuid, jsonb) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.set_org_default_brand(uuid, jsonb) TO authenticated, service_role;

-- ----------------------------------------------------------------------------
-- 2. New agent defaults
-- ----------------------------------------------------------------------------
-- The column default is a marker the insert trigger below resolves; a client that sends its own policy keeps it.
ALTER TABLE public.agents ALTER COLUMN signup_policy SET DEFAULT '{"mode":"invite","org_default":true}'::jsonb;

-- Same as agents_rbac's, except that the default (now the marker) is what needs no agents.access on insert.
CREATE OR REPLACE FUNCTION public.agents_permission_check() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    uid uuid := auth.uid();
BEGIN
    IF uid IS NULL THEN
        RETURN NEW;
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.signup_policy IS DISTINCT FROM '{"mode":"invite","org_default":true}'::jsonb
           AND NOT public.user_has_org_permission(uid, NEW.org_id, 'agents.access') THEN
            RAISE EXCEPTION 'Permission denied: agents.access' USING ERRCODE = '42501';
        END IF;
        RETURN NEW;
    END IF;
    IF (NEW.name, NEW.brand, NEW.model, NEW.allowed_connectors, NEW.allowed_tools)
       IS DISTINCT FROM (OLD.name, OLD.brand, OLD.model, OLD.allowed_connectors, OLD.allowed_tools)
       AND NOT public.user_has_org_permission(uid, OLD.org_id, 'agents.edit') THEN
        RAISE EXCEPTION 'Permission denied: agents.edit' USING ERRCODE = '42501';
    END IF;
    IF NEW.signup_policy IS DISTINCT FROM OLD.signup_policy
       AND NOT public.user_has_org_permission(uid, OLD.org_id, 'agents.access') THEN
        RAISE EXCEPTION 'Permission denied: agents.access' USING ERRCODE = '42501';
    END IF;
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION public.agents_permission_check() FROM PUBLIC, anon, authenticated;

-- Named to run after agents_permission_check_on_write (triggers fire in name order).
CREATE OR REPLACE FUNCTION public.agents_insert_defaults() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    domains jsonb;
    org_brand jsonb;
BEGIN
    IF NEW.signup_policy ? 'org_default' THEN
        SELECT pg_catalog.jsonb_agg(d.domain ORDER BY d.domain) INTO domains
            FROM public.organisation_domains d WHERE d.org_id = NEW.org_id AND d.verified;
        NEW.signup_policy := CASE WHEN domains IS NULL THEN '{"mode":"invite"}'::jsonb
                                  ELSE pg_catalog.jsonb_build_object('mode', 'domain', 'domains', domains) END;
    END IF;
    IF NOT (COALESCE(NEW.brand, '{}'::jsonb) ? 'accent') THEN
        SELECT o.default_brand INTO org_brand FROM public.agent_org_defaults o WHERE o.org_id = NEW.org_id;
        IF org_brand ? 'accent' THEN
            NEW.brand := COALESCE(NEW.brand, '{}'::jsonb)
                || pg_catalog.jsonb_strip_nulls(pg_catalog.jsonb_build_object(
                       'accent', org_brand->'accent', 'accent_dark', org_brand->'accent_dark'));
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION public.agents_insert_defaults() FROM PUBLIC, anon, authenticated;
DROP TRIGGER IF EXISTS agents_zz_insert_defaults ON public.agents;
CREATE TRIGGER agents_zz_insert_defaults BEFORE INSERT ON public.agents
    FOR EACH ROW EXECUTE FUNCTION public.agents_insert_defaults();

-- ----------------------------------------------------------------------------
-- 3. Email domains
-- ----------------------------------------------------------------------------
-- The org that already claimed an email domain (create-org names it); null when unclaimed.
CREATE OR REPLACE FUNCTION public.organisation_domain_owner(p_domain text)
RETURNS text LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
    SELECT o.name FROM public.organisation_domains od JOIN public.organisations o ON o.id = od.org_id
    WHERE auth.uid() IS NOT NULL
      AND od.domain = pg_catalog.lower(pg_catalog.ltrim(pg_catalog.btrim(COALESCE(p_domain, '')), '@'))
    ORDER BY od.verified DESC LIMIT 1;
$$;
REVOKE ALL ON FUNCTION public.organisation_domain_owner(text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.organisation_domain_owner(text) TO authenticated, service_role;

CREATE OR REPLACE FUNCTION public.org_email_domains(p_org_id uuid)
RETURNS TABLE (domain text, verified boolean) LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
#variable_conflict use_column
BEGIN
    PERFORM public.org_console_require_view(p_org_id);
    RETURN QUERY SELECT d.domain::text, d.verified FROM public.organisation_domains d
        WHERE d.org_id = p_org_id ORDER BY d.verified DESC, d.domain;
END;
$$;
REVOKE ALL ON FUNCTION public.org_email_domains(uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.org_email_domains(uuid) TO authenticated, service_role;

-- ----------------------------------------------------------------------------
-- 4. Audit rows keep the agent's name
-- ----------------------------------------------------------------------------
-- Stamped on insert (the log is append-only); a delete's row takes the last name the log saw.
CREATE OR REPLACE FUNCTION public.agent_audit_log_stamp_name() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    n text;
BEGIN
    IF NEW.agent_id IS NULL OR NEW.detail ? 'agent_name' THEN
        RETURN NEW;
    END IF;
    SELECT a.name INTO n FROM public.agents a WHERE a.id = NEW.agent_id;
    IF n IS NULL THEN
        SELECT COALESCE(l.detail->>'agent_name', l.detail->>'name') INTO n FROM public.agent_audit_log l
            WHERE l.agent_id = NEW.agent_id AND (l.detail ? 'agent_name' OR (l.action = 'agent.create' AND l.detail ? 'name'))
            ORDER BY l.id DESC LIMIT 1;
    END IF;
    IF n IS NOT NULL THEN
        NEW.detail := COALESCE(NEW.detail, '{}'::jsonb) || pg_catalog.jsonb_build_object('agent_name', n);
    END IF;
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION public.agent_audit_log_stamp_name() FROM PUBLIC, anon, authenticated;
DROP TRIGGER IF EXISTS agent_audit_log_stamp_name ON public.agent_audit_log;
CREATE TRIGGER agent_audit_log_stamp_name BEFORE INSERT ON public.agent_audit_log
    FOR EACH ROW EXECUTE FUNCTION public.agent_audit_log_stamp_name();
CREATE INDEX IF NOT EXISTS idx_agent_audit_log_agent ON public.agent_audit_log (agent_id, id DESC) WHERE agent_id IS NOT NULL;

DROP FUNCTION IF EXISTS public.org_audit_log(uuid, int, timestamptz);
CREATE FUNCTION public.org_audit_log(p_org_id uuid, p_limit int DEFAULT 50, p_before timestamptz DEFAULT NULL)
RETURNS TABLE (id bigint, agent_id uuid, agent_slug text, agent_name text, actor uuid, actor_email text, action text,
               detail jsonb, at timestamptz)
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
#variable_conflict use_column
BEGIN
    PERFORM public.org_console_require_view(p_org_id);
    RETURN QUERY
        SELECT l.id, l.agent_id, COALESCE(a.slug, l.detail->>'slug'),
               COALESCE(a.name, l.detail->>'agent_name',
                        (SELECT COALESCE(p.detail->>'agent_name', p.detail->>'name') FROM public.agent_audit_log p
                         WHERE p.agent_id = l.agent_id AND (p.detail ? 'agent_name' OR (p.action = 'agent.create' AND p.detail ? 'name'))
                         ORDER BY p.id DESC LIMIT 1)),
               l.actor, u.email::text, l.action, l.detail, l.at
        FROM public.agent_audit_log l
        LEFT JOIN public.agents a ON a.id = l.agent_id
        LEFT JOIN auth.users u ON u.id = l.actor
        WHERE l.org_id = p_org_id AND (p_before IS NULL OR l.at < p_before)
        ORDER BY l.at DESC, l.id DESC
        LIMIT LEAST(GREATEST(COALESCE(p_limit, 50), 1), 200);
END;
$$;
REVOKE ALL ON FUNCTION public.org_audit_log(uuid, int, timestamptz) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.org_audit_log(uuid, int, timestamptz) TO authenticated, service_role;

-- ----------------------------------------------------------------------------
-- 5. Platform settings history, paged
-- ----------------------------------------------------------------------------
DROP FUNCTION IF EXISTS public.platform_settings_history(int);
CREATE OR REPLACE FUNCTION public.platform_settings_history(p_limit int DEFAULT 20, p_before timestamptz DEFAULT NULL)
RETURNS TABLE (at timestamptz, actor_email text, key text, from_value jsonb, to_value jsonb)
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
#variable_conflict use_column
BEGIN
    IF auth.uid() IS NULL OR NOT public.is_user_admin(auth.uid()) THEN
        RAISE EXCEPTION 'Permission denied' USING ERRCODE = '42501';
    END IF;
    RETURN QUERY
        SELECT l.at, u.email::text, l.detail->>'key', l.detail->'from', l.detail->'to'
        FROM public.platform_audit_log l LEFT JOIN auth.users u ON u.id = l.actor
        WHERE l.action = 'platform.flag_set' AND (p_before IS NULL OR l.at < p_before)
        ORDER BY l.at DESC, l.id DESC
        LIMIT LEAST(GREATEST(COALESCE(p_limit, 20), 1), 100);
END;
$$;
REVOKE ALL ON FUNCTION public.platform_settings_history(int, timestamptz) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.platform_settings_history(int, timestamptz) TO authenticated, service_role;

-- ----------------------------------------------------------------------------
-- 6. Reasons are required to suspend or to reject in review
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.review_agent(p_agent_id uuid, p_approve boolean, p_reason text DEFAULT NULL)
RETURNS public.agent_status LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    a public.agents%ROWTYPE;
    reason text := NULLIF(pg_catalog.btrim(COALESCE(p_reason, '')), '');
    new_status public.agent_status;
    version text;
BEGIN
    IF auth.uid() IS NULL OR NOT public.is_user_admin(auth.uid()) THEN
        RAISE EXCEPTION 'Permission denied' USING ERRCODE = '42501';
    END IF;
    IF NOT p_approve AND reason IS NULL THEN
        RAISE EXCEPTION 'reason_required' USING ERRCODE = 'P0001';
    END IF;
    IF pg_catalog.char_length(COALESCE(reason, '')) > 500 THEN
        RAISE EXCEPTION 'reason_too_long' USING ERRCODE = 'P0001';
    END IF;
    SELECT * INTO a FROM public.agents WHERE id = p_agent_id FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'agent_not_found' USING ERRCODE = 'P0001';
    END IF;
    IF a.status = 'live' AND a.pending_version IS NOT NULL THEN
        version := a.pending_version;
        new_status := 'live';
        IF p_approve THEN
            UPDATE public.agents SET live_version = a.pending_version, pending_version = NULL, review_note = NULL
                WHERE id = p_agent_id;
        ELSE
            UPDATE public.agents SET pending_version = NULL, review_note = reason WHERE id = p_agent_id;
        END IF;
        INSERT INTO public.agent_audit_log (org_id, agent_id, actor, action, detail)
            VALUES (a.org_id, a.id, auth.uid(), CASE WHEN p_approve THEN 'agent.approve' ELSE 'agent.reject' END,
                    pg_catalog.jsonb_strip_nulls(pg_catalog.jsonb_build_object('version', version, 'reason', reason)));
    ELSIF a.status = 'pending_review' THEN
        version := a.live_version;
        new_status := CASE WHEN p_approve THEN 'live' ELSE 'draft' END;
        -- agents_audit writes agent.approve / agent.reject on the status change.
        UPDATE public.agents SET status = new_status, review_note = CASE WHEN p_approve THEN NULL ELSE reason END
            WHERE id = p_agent_id;
    ELSE
        RAISE EXCEPTION 'agent_not_pending_review' USING ERRCODE = 'P0001';
    END IF;
    INSERT INTO public.platform_audit_log (actor, action, detail)
        VALUES (auth.uid(), CASE WHEN p_approve THEN 'agent.approve' ELSE 'agent.reject' END,
                pg_catalog.jsonb_strip_nulls(pg_catalog.jsonb_build_object(
                    'agent_id', a.id, 'slug', a.slug, 'version', version, 'reason', reason)));
    RETURN new_status;
END;
$$;

CREATE OR REPLACE FUNCTION public.suspend_agent(p_agent_id uuid, p_reason text DEFAULT NULL)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    a public.agents%ROWTYPE;
    reason text := NULLIF(pg_catalog.btrim(COALESCE(p_reason, '')), '');
BEGIN
    IF auth.uid() IS NULL OR NOT public.is_user_admin(auth.uid()) THEN
        RAISE EXCEPTION 'Permission denied' USING ERRCODE = '42501';
    END IF;
    IF reason IS NULL THEN
        RAISE EXCEPTION 'reason_required' USING ERRCODE = 'P0001';
    END IF;
    IF pg_catalog.char_length(reason) > 500 THEN
        RAISE EXCEPTION 'reason_too_long' USING ERRCODE = 'P0001';
    END IF;
    SELECT * INTO a FROM public.agents WHERE id = p_agent_id FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'agent_not_found' USING ERRCODE = 'P0001';
    END IF;
    IF a.status = 'suspended' THEN
        RAISE EXCEPTION 'agent_already_suspended' USING ERRCODE = 'P0001';
    END IF;
    UPDATE public.agents SET status = 'suspended', status_before_suspend = a.status,
                             suspended_at = pg_catalog.now(), suspended_reason = reason
        WHERE id = p_agent_id;
    INSERT INTO public.platform_audit_log (actor, action, detail)
        VALUES (auth.uid(), 'agent.suspend', pg_catalog.jsonb_strip_nulls(pg_catalog.jsonb_build_object(
            'agent_id', a.id, 'slug', a.slug, 'from_status', a.status, 'reason', reason)));
END;
$$;
