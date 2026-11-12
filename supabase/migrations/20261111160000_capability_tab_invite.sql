-- Max web chat's Invite tab (invite links) is org-switchable like the other tabs: tabs.invite.

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
                                                   'community', 'memory', 'invite')
                                  OR pg_catalog.jsonb_typeof(t.value) <> 'boolean')));
$$;

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
            -- Flattened paths (evolve, tabs.memory) whose effective value changed: unset reads as on, except
            -- own_connectors, whose unset means the legacy rule.
            SELECT array_agg(k ORDER BY k) INTO cap_keys FROM (
                SELECT k FROM unnest(ARRAY['evolve', 'own_connectors', 'tabs.shared', 'tabs.activity', 'tabs.vault',
                                           'tabs.connectors', 'tabs.skills', 'tabs.community', 'tabs.memory', 'tabs.invite']) k
                WHERE CASE WHEN k = 'own_connectors' THEN NEW.capabilities -> k
                           ELSE COALESCE(NEW.capabilities #> pg_catalog.string_to_array(k, '.'), 'true'::jsonb) END
                      IS DISTINCT FROM
                      CASE WHEN k = 'own_connectors' THEN OLD.capabilities -> k
                           ELSE COALESCE(OLD.capabilities #> pg_catalog.string_to_array(k, '.'), 'true'::jsonb) END) ks;
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
