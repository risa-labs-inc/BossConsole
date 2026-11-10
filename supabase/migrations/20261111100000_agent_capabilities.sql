-- Per-agent capabilities: evolve, side-panel tabs and people's own connectors, each on/off.
-- The provisioner copies the column into agents.json `policy`, and Max applies it live.
-- A missing key means on, so '{}' (every existing agent) keeps today's behaviour.

INSERT INTO public.permissions (name, description, is_system) VALUES
    ('agents.capabilities', 'Turn evolve, side-panel tabs and own connectors on or off for agents', true)
ON CONFLICT (name) DO UPDATE SET is_system = true, description = EXCLUDED.description;

-- Appended so existing positions keep their order. Org admins hold it through the catalog.
CREATE OR REPLACE FUNCTION public.agents_permission_catalog() RETURNS text[]
    LANGUAGE sql IMMUTABLE SET search_path = '' AS $$
    SELECT ARRAY['agents.view', 'agents.edit', 'agents.publish', 'agents.evolve', 'agents.access', 'agents.domains',
                 'agents.capabilities'];
$$;

-- Existing orgs' admin roles, as seed_org_agent_roles gives new ones.
INSERT INTO public.role_permissions (role_id, permission_id)
    SELECT orl.role_id, p.id FROM public.organisation_roles orl CROSS JOIN public.permissions p
    WHERE orl.kind = 'admin' AND p.name = 'agents.capabilities'
    ON CONFLICT (role_id, permission_id) DO NOTHING;

CREATE OR REPLACE FUNCTION public.agent_capabilities_valid(p jsonb) RETURNS boolean
    LANGUAGE sql IMMUTABLE SET search_path = '' AS $$
    SELECT pg_catalog.jsonb_typeof(p) = 'object'
       AND NOT EXISTS (SELECT 1 FROM pg_catalog.jsonb_each(p) e
                       WHERE e.key NOT IN ('evolve', 'own_connectors', 'tabs')
                          OR (e.key <> 'tabs' AND pg_catalog.jsonb_typeof(e.value) <> 'boolean'))
       AND (NOT (p ? 'tabs') OR (
               pg_catalog.jsonb_typeof(p->'tabs') = 'object'
               AND NOT EXISTS (SELECT 1 FROM pg_catalog.jsonb_each(p->'tabs') t
                               WHERE t.key NOT IN ('shared', 'activity', 'vault', 'connectors', 'skills',
                                                   'community', 'memory')
                                  OR pg_catalog.jsonb_typeof(t.value) <> 'boolean')));
$$;

ALTER TABLE public.agents ADD COLUMN IF NOT EXISTS capabilities jsonb NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE public.agents DROP CONSTRAINT IF EXISTS agents_capabilities_valid;
ALTER TABLE public.agents ADD CONSTRAINT agents_capabilities_valid CHECK (public.agent_capabilities_valid(capabilities));

GRANT UPDATE (capabilities) ON public.agents TO authenticated;

-- A holder of only agents.capabilities reaches the row; agents_permission_check still limits them to that column.
DROP POLICY IF EXISTS "agents update" ON public.agents;
CREATE POLICY "agents update" ON public.agents FOR UPDATE TO authenticated
    USING (public.has_org_permission(org_id, 'agents.edit') OR public.has_org_permission(org_id, 'agents.access')
           OR public.has_org_permission(org_id, 'agents.capabilities'))
    WITH CHECK (public.has_org_permission(org_id, 'agents.edit') OR public.has_org_permission(org_id, 'agents.access')
                OR public.has_org_permission(org_id, 'agents.capabilities'));

-- Body as in 20261023100000, plus the capabilities check.
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
        IF NEW.capabilities IS DISTINCT FROM '{}'::jsonb
           AND NOT public.user_has_org_permission(uid, NEW.org_id, 'agents.capabilities') THEN
            RAISE EXCEPTION 'Permission denied: agents.capabilities' USING ERRCODE = '42501';
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
    IF NEW.capabilities IS DISTINCT FROM OLD.capabilities
       AND NOT public.user_has_org_permission(uid, OLD.org_id, 'agents.capabilities') THEN
        RAISE EXCEPTION 'Permission denied: agents.capabilities' USING ERRCODE = '42501';
    END IF;
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION public.agents_permission_check() FROM PUBLIC, anon, authenticated;

-- Body as in 20261022150000, plus 'capabilities' in settings_change (with the keys whose value changed).
CREATE OR REPLACE FUNCTION public.agents_audit() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    actor uuid := public.agent_audit_actor();
    changed text[];
    brand_keys text[];
    cap_keys text[];
    act text;
    via text := NULLIF(pg_catalog.current_setting('agents.audit_via', true), '');
BEGIN
    IF TG_OP = 'INSERT' THEN
        INSERT INTO public.agent_audit_log (org_id, agent_id, actor, action, detail)
            VALUES (NEW.org_id, NEW.id, actor, 'agent.create', jsonb_build_object('slug', NEW.slug, 'name', NEW.name));
        RETURN NULL;
    ELSIF TG_OP = 'DELETE' THEN
        INSERT INTO public.agent_audit_log (org_id, agent_id, actor, action, detail)
            VALUES (OLD.org_id, OLD.id, actor, 'agent.delete', jsonb_build_object('slug', OLD.slug));
        RETURN NULL;
    END IF;
    IF NEW.brand IS DISTINCT FROM OLD.brand THEN
        SELECT array_agg(k ORDER BY k) INTO brand_keys FROM (
            SELECT k FROM pg_catalog.jsonb_object_keys(NEW.brand) k
            UNION SELECT k FROM pg_catalog.jsonb_object_keys(OLD.brand) k) ks
        WHERE NEW.brand->k IS DISTINCT FROM OLD.brand->k;
        INSERT INTO public.agent_audit_log (org_id, agent_id, actor, action, detail)
            VALUES (NEW.org_id, NEW.id, actor, 'agent.brand_change', jsonb_build_object('keys', to_jsonb(brand_keys)) || CASE WHEN via IS NULL THEN '{}'::jsonb ELSE jsonb_build_object('via', via) END);
    END IF;
    changed := array_remove(ARRAY[
        CASE WHEN NEW.name IS DISTINCT FROM OLD.name THEN 'name' END,
        CASE WHEN NEW.model IS DISTINCT FROM OLD.model THEN 'model' END,
        CASE WHEN NEW.signup_policy IS DISTINCT FROM OLD.signup_policy THEN 'signup_policy' END,
        CASE WHEN NEW.allowed_connectors IS DISTINCT FROM OLD.allowed_connectors THEN 'allowed_connectors' END,
        CASE WHEN NEW.allowed_tools IS DISTINCT FROM OLD.allowed_tools THEN 'allowed_tools' END,
        CASE WHEN NEW.plugin_id IS DISTINCT FROM OLD.plugin_id THEN 'plugin_id' END,
        CASE WHEN NEW.capabilities IS DISTINCT FROM OLD.capabilities THEN 'capabilities' END], NULL);
    IF cardinality(changed) > 0 THEN
        IF 'capabilities' = ANY (changed) THEN
            -- Flattened paths (evolve, tabs.memory) whose stored value changed.
            SELECT array_agg(k ORDER BY k) INTO cap_keys FROM (
                SELECT k FROM unnest(ARRAY['evolve', 'own_connectors', 'tabs.shared', 'tabs.activity', 'tabs.vault',
                                           'tabs.connectors', 'tabs.skills', 'tabs.community', 'tabs.memory']) k
                WHERE NEW.capabilities #> pg_catalog.string_to_array(k, '.')
                      IS DISTINCT FROM OLD.capabilities #> pg_catalog.string_to_array(k, '.')) ks;
        END IF;
        INSERT INTO public.agent_audit_log (org_id, agent_id, actor, action, detail)
            VALUES (NEW.org_id, NEW.id, actor, 'agent.settings_change', jsonb_build_object('fields', to_jsonb(changed))
                || CASE WHEN cap_keys IS NULL THEN '{}'::jsonb
                        ELSE jsonb_build_object('capabilities', NEW.capabilities, 'capability_keys', to_jsonb(cap_keys)) END);
    END IF;
    IF NEW.live_version IS DISTINCT FROM OLD.live_version THEN
        INSERT INTO public.agent_audit_log (org_id, agent_id, actor, action, detail)
            VALUES (NEW.org_id, NEW.id, actor, 'agent.publish', jsonb_build_object(
                'version', NEW.live_version, 'from_version', OLD.live_version,
                'status', NEW.status, 'from_status', OLD.status));
    ELSIF NEW.status IS DISTINCT FROM OLD.status THEN
        act := CASE WHEN NEW.status = 'suspended' THEN 'agent.suspend'
                    WHEN OLD.status = 'suspended' THEN 'agent.unsuspend'
                    WHEN OLD.status = 'pending_review' AND NEW.status = 'live' THEN 'agent.approve'
                    WHEN OLD.status = 'pending_review' AND NEW.status = 'draft' THEN 'agent.reject'
                    ELSE 'agent.status' END;
        INSERT INTO public.agent_audit_log (org_id, agent_id, actor, action, detail)
            VALUES (NEW.org_id, NEW.id, actor, act, jsonb_build_object('status', NEW.status, 'from_status', OLD.status));
    END IF;
    RETURN NULL;
END;
$$;
