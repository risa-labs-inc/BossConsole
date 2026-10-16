-- ============================================================================
-- Agents platform: business-owned Fluck agents served at <slug>.bossconsole.ai
-- ============================================================================
-- Org admins (public.is_org_admin) manage their own agents. status and live_version move only
-- through the definer RPCs below (column privileges deny direct writes). Platform admin =
-- public.is_user_admin(auth.uid()). The persona and engine live in the adapter plugin published
-- to the plugin store; the console records the live store version through publish_agent.
-- ============================================================================

CREATE EXTENSION IF NOT EXISTS citext WITH SCHEMA extensions;

DO $$ BEGIN
    CREATE TYPE public.agent_status AS ENUM ('draft', 'pending_review', 'live', 'suspended');
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

CREATE TABLE IF NOT EXISTS public.platform_flags (
    key   text PRIMARY KEY,
    value jsonb NOT NULL
);
INSERT INTO public.platform_flags (key, value) VALUES ('agent_creation_open', 'false'::jsonb)
    ON CONFLICT (key) DO NOTHING;

CREATE TABLE IF NOT EXISTS public.agent_reserved_slugs (slug text PRIMARY KEY);
INSERT INTO public.agent_reserved_slugs (slug) VALUES
    ('www'), ('api'), ('app'), ('console'), ('admin'), ('auth'), ('mail'), ('status'),
    ('docs'), ('help'), ('support'), ('blog'), ('cdn'), ('static'), ('assets'), ('login'),
    ('signup'), ('billing'), ('max'), ('fluck'), ('optimist'), ('boss'), ('bossconsole'), ('risa')
    ON CONFLICT DO NOTHING;

CREATE TABLE IF NOT EXISTS public.agents (
    id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    slug                 text NOT NULL UNIQUE CHECK (slug ~ '^[a-z][a-z0-9-]{1,30}[a-z0-9]$'),
    org_id               uuid NOT NULL REFERENCES public.organisations(id) ON DELETE CASCADE,
    name                 text NOT NULL CHECK (btrim(name) <> ''),
    status               public.agent_status NOT NULL DEFAULT 'draft',
    brand                jsonb NOT NULL DEFAULT '{}'::jsonb,
    model                jsonb NOT NULL DEFAULT '{"providerId":"managed:boss-ai","modelId":"optimist"}'::jsonb,
    signup_policy        jsonb NOT NULL DEFAULT '{"mode":"open"}'::jsonb
        CHECK (signup_policy->>'mode' IN ('open', 'domain', 'invite')),
    allowed_connectors   text[],
    allowed_tools        text[],
    -- Id of the agent's adapter plugin in the plugin store; set once by the org admin at creation.
    plugin_id            text UNIQUE
        CHECK (plugin_id ~ '^ai\.rever\.boss\.plugin\.dynamic\.agent\.[a-z][a-z0-9.]*$'),
    live_version         text,
    created_by           uuid REFERENCES auth.users(id) ON DELETE SET NULL,
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_agents_org ON public.agents (org_id);

CREATE TABLE IF NOT EXISTS public.agent_installs (
    user_id     uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    agent_id    uuid NOT NULL REFERENCES public.agents(id) ON DELETE CASCADE,
    instance_id text NOT NULL,
    port        int NOT NULL,
    data_dir    text NOT NULL CHECK (data_dir !~ '(^/|(^|/)\.\.(/|$))'),
    state       text NOT NULL CHECK (state IN ('pending', 'running', 'failed', 'removed')),
    detail      text,
    updated_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, agent_id),
    UNIQUE (instance_id, port)
);

CREATE TABLE IF NOT EXISTS public.agent_invites (
    agent_id   uuid NOT NULL REFERENCES public.agents(id) ON DELETE CASCADE,
    email      extensions.citext NOT NULL,
    invited_by uuid REFERENCES auth.users(id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (agent_id, email)
);

-- ----------------------------------------------------------------------------
-- Triggers: reserved slugs, updated_at
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.agents_guard() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
BEGIN
    IF TG_OP = 'INSERT' OR NEW.slug IS DISTINCT FROM OLD.slug THEN
        IF EXISTS (SELECT 1 FROM public.agent_reserved_slugs r WHERE r.slug = NEW.slug) THEN
            RAISE EXCEPTION 'slug_reserved' USING ERRCODE = 'P0001';
        END IF;
    END IF;
    IF TG_OP = 'UPDATE' AND OLD.plugin_id IS NOT NULL AND NEW.plugin_id IS DISTINCT FROM OLD.plugin_id THEN
        RAISE EXCEPTION 'plugin_id_immutable' USING ERRCODE = 'P0001';
    END IF;
    NEW.updated_at := now();
    RETURN NEW;
END;
$$;
DROP TRIGGER IF EXISTS agents_guard_on_write ON public.agents;
CREATE TRIGGER agents_guard_on_write BEFORE INSERT OR UPDATE ON public.agents
    FOR EACH ROW EXECUTE FUNCTION public.agents_guard();

CREATE OR REPLACE FUNCTION public.agent_installs_touch() RETURNS trigger
    LANGUAGE plpgsql SET search_path = '' AS $$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END;
$$;
DROP TRIGGER IF EXISTS agent_installs_touch_on_write ON public.agent_installs;
CREATE TRIGGER agent_installs_touch_on_write BEFORE INSERT OR UPDATE ON public.agent_installs
    FOR EACH ROW EXECUTE FUNCTION public.agent_installs_touch();

-- Subject is always auth.uid(), so this is safe to expose to authenticated.
CREATE OR REPLACE FUNCTION public.is_agent_org_admin(p_agent_id uuid) RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
    SELECT EXISTS (
        SELECT 1 FROM public.agents a
        WHERE a.id = p_agent_id AND public.is_org_admin(a.org_id)
    );
$$;
REVOKE ALL ON FUNCTION public.is_agent_org_admin(uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.is_agent_org_admin(uuid) TO authenticated, service_role;

-- ----------------------------------------------------------------------------
-- Grants and RLS. Table-level grants are revoked first: default privileges hand anon and
-- authenticated everything on new tables. status/live_* have no column grant for clients.
-- ----------------------------------------------------------------------------
REVOKE ALL ON public.agents, public.agent_installs, public.agent_invites, public.agent_reserved_slugs,
    public.platform_flags FROM PUBLIC, anon, authenticated;
GRANT ALL ON public.agents, public.agent_installs, public.agent_invites, public.agent_reserved_slugs,
    public.platform_flags TO service_role;

GRANT SELECT ON public.agents TO authenticated;
GRANT INSERT (slug, org_id, name, brand, model, signup_policy, allowed_connectors, allowed_tools, plugin_id, created_by)
    ON public.agents TO authenticated;
GRANT UPDATE (name, brand, model, signup_policy, allowed_connectors, allowed_tools)
    ON public.agents TO authenticated;
GRANT SELECT, INSERT, DELETE ON public.agent_invites TO authenticated;
GRANT SELECT ON public.agent_installs TO authenticated;

ALTER TABLE public.agents ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_installs ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_invites ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_reserved_slugs ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.platform_flags ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "agents org admin select" ON public.agents;
CREATE POLICY "agents org admin select" ON public.agents
    FOR SELECT TO authenticated USING (public.is_org_admin(org_id));
DROP POLICY IF EXISTS "agents org admin insert" ON public.agents;
CREATE POLICY "agents org admin insert" ON public.agents
    FOR INSERT TO authenticated
    WITH CHECK (public.is_org_admin(org_id) AND created_by = (SELECT auth.uid()));
DROP POLICY IF EXISTS "agents org admin update" ON public.agents;
CREATE POLICY "agents org admin update" ON public.agents
    FOR UPDATE TO authenticated
    USING (public.is_org_admin(org_id)) WITH CHECK (public.is_org_admin(org_id));

DROP POLICY IF EXISTS "agent_invites admin all" ON public.agent_invites;
CREATE POLICY "agent_invites admin all" ON public.agent_invites
    FOR ALL TO authenticated
    USING (public.is_agent_org_admin(agent_id))
    WITH CHECK (public.is_agent_org_admin(agent_id));

DROP POLICY IF EXISTS "agent_installs owner select" ON public.agent_installs;
CREATE POLICY "agent_installs owner select" ON public.agent_installs
    FOR SELECT TO authenticated USING ((SELECT auth.uid()) = user_id);

COMMENT ON COLUMN public.agents.brand IS
    '{name, short_name, accent, logo_path}. logo_path convention: agent-brand/<agent_id>/<file> (public bucket agent-brand).';

-- ----------------------------------------------------------------------------
-- Public login-page view: owner-rights (not security_invoker) so anon sees live rows only.
-- ----------------------------------------------------------------------------
CREATE OR REPLACE VIEW public.agent_public WITH (security_barrier = true) AS
    SELECT a.slug, a.name, a.brand, a.signup_policy->>'mode' AS signup_mode, a.status, a.plugin_id
    FROM public.agents a
    WHERE a.status = 'live';
REVOKE ALL ON public.agent_public FROM PUBLIC, anon, authenticated;
GRANT SELECT ON public.agent_public TO anon, authenticated, service_role;

-- ----------------------------------------------------------------------------
-- RPCs
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.publish_agent(p_agent_id uuid, p_version text)
RETURNS public.agent_status LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    a public.agents%ROWTYPE;
    new_status public.agent_status;
BEGIN
    IF auth.uid() IS NULL THEN
        RAISE EXCEPTION 'Not signed in' USING ERRCODE = '42501';
    END IF;
    SELECT * INTO a FROM public.agents WHERE id = p_agent_id FOR UPDATE;
    IF NOT FOUND OR NOT public.is_org_admin(a.org_id) THEN
        RAISE EXCEPTION 'Permission denied' USING ERRCODE = '42501';
    END IF;
    IF a.status = 'suspended' THEN
        RAISE EXCEPTION 'agent_suspended' USING ERRCODE = 'P0001';
    END IF;
    IF p_version IS NULL OR p_version !~ '^[0-9]+\.[0-9]+\.[0-9]+([-+][0-9A-Za-z.+-]+)?$' THEN
        RAISE EXCEPTION 'invalid_version' USING ERRCODE = 'P0001';
    END IF;
    IF a.plugin_id IS NULL THEN
        RAISE EXCEPTION 'plugin_id_not_set' USING ERRCODE = 'P0001';
    END IF;
    new_status := CASE WHEN COALESCE((SELECT f.value = 'true'::jsonb FROM public.platform_flags f
                                      WHERE f.key = 'agent_creation_open'), false)
                       THEN 'live' ELSE 'pending_review' END;
    UPDATE public.agents SET live_version = p_version, status = new_status
        WHERE id = p_agent_id;
    RETURN new_status;
END;
$$;

CREATE OR REPLACE FUNCTION public.review_agent(p_agent_id uuid, p_approve boolean)
RETURNS public.agent_status LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    new_status public.agent_status := CASE WHEN p_approve THEN 'live' ELSE 'draft' END;
BEGIN
    IF auth.uid() IS NULL OR NOT public.is_user_admin(auth.uid()) THEN
        RAISE EXCEPTION 'Permission denied' USING ERRCODE = '42501';
    END IF;
    UPDATE public.agents SET status = new_status
        WHERE id = p_agent_id AND status = 'pending_review';
    IF NOT FOUND THEN
        RAISE EXCEPTION 'agent_not_pending_review' USING ERRCODE = 'P0001';
    END IF;
    RETURN new_status;
END;
$$;

CREATE OR REPLACE FUNCTION public.suspend_agent(p_agent_id uuid)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
BEGIN
    IF auth.uid() IS NULL OR NOT public.is_user_admin(auth.uid()) THEN
        RAISE EXCEPTION 'Permission denied' USING ERRCODE = '42501';
    END IF;
    UPDATE public.agents SET status = 'suspended' WHERE id = p_agent_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'agent_not_found' USING ERRCODE = 'P0001';
    END IF;
END;
$$;

CREATE OR REPLACE FUNCTION public.agent_can_join(p_slug text, p_email text)
RETURNS boolean LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    a public.agents%ROWTYPE;
    norm text := pg_catalog.lower(pg_catalog.btrim(COALESCE(p_email, '')));
    mode text;
BEGIN
    IF norm !~ '^[^@\s]+@[^@\s]+$' THEN
        RETURN false;
    END IF;
    SELECT * INTO a FROM public.agents WHERE slug = p_slug AND status = 'live';
    IF NOT FOUND THEN
        RETURN false;
    END IF;
    mode := a.signup_policy->>'mode';
    IF mode = 'open' THEN
        RETURN true;
    ELSIF mode = 'domain' THEN
        RETURN EXISTS (
            SELECT 1 FROM pg_catalog.jsonb_array_elements_text(
                CASE WHEN pg_catalog.jsonb_typeof(a.signup_policy->'domains') = 'array'
                     THEN a.signup_policy->'domains' ELSE '[]'::jsonb END) d
            WHERE pg_catalog.lower(pg_catalog.btrim(d, ' @')) = pg_catalog.split_part(norm, '@', 2));
    ELSIF mode = 'invite' THEN
        RETURN EXISTS (SELECT 1 FROM public.agent_invites i
                       WHERE i.agent_id = a.id AND pg_catalog.lower(i.email::text) = norm);
    END IF;
    RETURN false;
END;
$$;

REVOKE ALL ON FUNCTION public.publish_agent(uuid, text), public.review_agent(uuid, boolean),
    public.suspend_agent(uuid),
    public.agent_can_join(text, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.publish_agent(uuid, text), public.review_agent(uuid, boolean),
    public.suspend_agent(uuid) TO authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.agent_can_join(text, text) TO anon, authenticated, service_role;

-- ----------------------------------------------------------------------------
-- Bucket: agent-brand is public (logos)
-- ----------------------------------------------------------------------------
INSERT INTO storage.buckets (id, name, public, file_size_limit, allowed_mime_types) VALUES
    ('agent-brand', 'agent-brand', true, 2 * 1024 * 1024,
     ARRAY['image/png', 'image/jpeg', 'image/svg+xml', 'image/webp'])
ON CONFLICT (id) DO UPDATE SET public = EXCLUDED.public,
    file_size_limit = EXCLUDED.file_size_limit, allowed_mime_types = EXCLUDED.allowed_mime_types;

-- Folder rule for agent-brand: the first path segment is an agent id
-- whose org the caller administers. agent-brand is public, so reads need no policy there.
CREATE OR REPLACE FUNCTION public.is_agent_folder_admin(p_name text) RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
    SELECT CASE WHEN (storage.foldername(p_name))[1] ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
                THEN public.is_agent_org_admin(((storage.foldername(p_name))[1])::uuid)
                ELSE false END;
$$;
REVOKE ALL ON FUNCTION public.is_agent_folder_admin(text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.is_agent_folder_admin(text) TO authenticated, service_role;

DROP POLICY IF EXISTS "agent-brand admin insert" ON storage.objects;
CREATE POLICY "agent-brand admin insert" ON storage.objects FOR INSERT TO authenticated
    WITH CHECK (bucket_id = 'agent-brand' AND public.is_agent_folder_admin(name));
DROP POLICY IF EXISTS "agent-brand admin update" ON storage.objects;
CREATE POLICY "agent-brand admin update" ON storage.objects FOR UPDATE TO authenticated
    USING (bucket_id = 'agent-brand' AND public.is_agent_folder_admin(name))
    WITH CHECK (bucket_id = 'agent-brand' AND public.is_agent_folder_admin(name));
DROP POLICY IF EXISTS "agent-brand admin delete" ON storage.objects;
CREATE POLICY "agent-brand admin delete" ON storage.objects FOR DELETE TO authenticated
    USING (bucket_id = 'agent-brand' AND public.is_agent_folder_admin(name));


-- ----------------------------------------------------------------------------
-- platform_flags: flags hold no secrets, so every signed-in user may read them (the console
-- reads agent_creation_open); only platform admins may change a value.
-- ----------------------------------------------------------------------------
GRANT SELECT ON public.platform_flags TO authenticated;
GRANT UPDATE (value) ON public.platform_flags TO authenticated;
DROP POLICY IF EXISTS "platform_flags read" ON public.platform_flags;
CREATE POLICY "platform_flags read" ON public.platform_flags FOR SELECT TO authenticated USING (true);
DROP POLICY IF EXISTS "platform_flags admin update" ON public.platform_flags;
CREATE POLICY "platform_flags admin update" ON public.platform_flags FOR UPDATE TO authenticated
    USING (public.is_user_admin((SELECT auth.uid())))
    WITH CHECK (public.is_user_admin((SELECT auth.uid())));

-- ----------------------------------------------------------------------------
-- Platform-admin review queue
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.list_pending_agents()
RETURNS TABLE (id uuid, slug text, name text, org_id uuid, org_name text, created_at timestamptz)
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
#variable_conflict use_column
BEGIN
    IF auth.uid() IS NULL OR NOT public.is_user_admin(auth.uid()) THEN
        RAISE EXCEPTION 'Permission denied' USING ERRCODE = '42501';
    END IF;
    RETURN QUERY
        SELECT a.id, a.slug, a.name, a.org_id, o.name, a.created_at
        FROM public.agents a JOIN public.organisations o ON o.id = a.org_id
        WHERE a.status = 'pending_review'
        ORDER BY a.created_at;
END;
$$;
REVOKE ALL ON FUNCTION public.list_pending_agents() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.list_pending_agents() TO authenticated, service_role;

-- ----------------------------------------------------------------------------
-- Provisioner bot: the provisioner signs in as an ordinary auth user (no service-role key) whose
-- access is exactly what the policies below grant. The bot user is created BY HAND (Auth admin) and
-- its id inserted once into agents_service_accounts by the service role / a migration; there is
-- no client path to that table.
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.agents_service_accounts (
    user_id    uuid PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
    purpose    text NOT NULL CHECK (purpose = 'provisioner'),
    created_at timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE public.agents_service_accounts ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.agents_service_accounts FROM PUBLIC, anon, authenticated;
GRANT ALL ON public.agents_service_accounts TO service_role;

CREATE OR REPLACE FUNCTION public.is_agents_provisioner() RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
    SELECT EXISTS (
        SELECT 1 FROM public.agents_service_accounts s
        WHERE s.user_id = auth.uid() AND s.purpose = 'provisioner'
    );
$$;
REVOKE ALL ON FUNCTION public.is_agents_provisioner() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.is_agents_provisioner() TO authenticated, service_role;

-- Read-only on agents; read/write (never delete) on installs.
-- Table SELECT grants for authenticated already exist above; these policies widen the rows only.
DROP POLICY IF EXISTS "agents provisioner select" ON public.agents;
CREATE POLICY "agents provisioner select" ON public.agents
    FOR SELECT TO authenticated USING (public.is_agents_provisioner());
GRANT INSERT (user_id, agent_id, instance_id, port, data_dir, state, detail)
    ON public.agent_installs TO authenticated;
GRANT UPDATE (user_id, agent_id, instance_id, port, data_dir, state, detail)
    ON public.agent_installs TO authenticated;
DROP POLICY IF EXISTS "agent_installs provisioner select" ON public.agent_installs;
CREATE POLICY "agent_installs provisioner select" ON public.agent_installs
    FOR SELECT TO authenticated USING (public.is_agents_provisioner());
DROP POLICY IF EXISTS "agent_installs provisioner insert" ON public.agent_installs;
CREATE POLICY "agent_installs provisioner insert" ON public.agent_installs
    FOR INSERT TO authenticated WITH CHECK (public.is_agents_provisioner());
DROP POLICY IF EXISTS "agent_installs provisioner update" ON public.agent_installs;
CREATE POLICY "agent_installs provisioner update" ON public.agent_installs
    FOR UPDATE TO authenticated
    USING (public.is_agents_provisioner()) WITH CHECK (public.is_agents_provisioner());
