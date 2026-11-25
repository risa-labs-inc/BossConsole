-- ============================================================================
-- Agents built from the org's own Max: a GitHub App installed on the organisation, one engine repo per
-- agent (created by the platform, seeded by the person's Max), builds imported from that repo's CI on
-- main, and the build each published adapter version bundles (its pin). Design: optimist
-- docs/custom-max-agents.md.
--
-- Every write goes through the agent-github edge function (service role) or a definer RPC here; clients
-- only read, under agents.view on the agent's organisation. No GitHub credential is ever stored: the App's
-- key is an edge-function secret and installation tokens are minted per call.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. Permission: connect the org's GitHub and create or link engine repos. Org admins hold it.
-- ----------------------------------------------------------------------------
INSERT INTO public.permissions (name, description, is_system) VALUES
    ('agents.github', 'Connect the organisation''s GitHub and create or link agent engine repositories', true)
ON CONFLICT (name) DO UPDATE SET is_system = true, description = EXCLUDED.description;

-- Appended so existing positions keep their order.
CREATE OR REPLACE FUNCTION public.agents_permission_catalog() RETURNS text[]
    LANGUAGE sql IMMUTABLE SET search_path = '' AS $$
    SELECT ARRAY['agents.view', 'agents.edit', 'agents.publish', 'agents.evolve', 'agents.access', 'agents.domains',
                 'agents.capabilities', 'agents.github'];
$$;

INSERT INTO public.role_permissions (role_id, permission_id)
    SELECT orl.role_id, p.id FROM public.organisation_roles orl CROSS JOIN public.permissions p
    WHERE orl.kind = 'admin' AND p.name = 'agents.github'
    ON CONFLICT (role_id, permission_id) DO NOTHING;

-- ----------------------------------------------------------------------------
-- 2. Tables.
-- ----------------------------------------------------------------------------
-- The org's installation of the platform GitHub App (one per org). installation_id is not a secret.
CREATE TABLE IF NOT EXISTS public.org_github_connections (
    org_id          uuid PRIMARY KEY REFERENCES public.organisations(id) ON DELETE CASCADE,
    installation_id bigint NOT NULL UNIQUE CHECK (installation_id > 0),
    account_login   text NOT NULL CHECK (account_login ~ '^[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})$'),
    account_id      bigint NOT NULL CHECK (account_id > 0),
    status          text NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'removed')),
    connected_by    uuid REFERENCES auth.users(id) ON DELETE SET NULL,
    connected_at    timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now()
);

-- Single-use state for the install redirect: binds the GitHub callback to the org and person who started it.
CREATE TABLE IF NOT EXISTS public.github_connect_states (
    nonce      text PRIMARY KEY CHECK (nonce ~ '^[A-Za-z0-9_-]{32,64}$'),
    org_id     uuid NOT NULL REFERENCES public.organisations(id) ON DELETE CASCADE,
    user_id    uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    expires_at timestamptz NOT NULL
);

-- An agent's engine repository. seeding: created, waiting for the person's Max to push stock and main;
-- ready: CI workflow written and main protected.
CREATE TABLE IF NOT EXISTS public.agent_engine_repos (
    agent_id     uuid PRIMARY KEY REFERENCES public.agents(id) ON DELETE CASCADE,
    org_id       uuid NOT NULL REFERENCES public.organisations(id) ON DELETE CASCADE,
    repo_owner   text NOT NULL CHECK (repo_owner ~ '^[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})$'),
    repo_name    text NOT NULL CHECK (repo_name ~ '^[A-Za-z0-9._-]{1,100}$' AND repo_name NOT IN ('.', '..')),
    repo_id      bigint NOT NULL CHECK (repo_id > 0),
    state        text NOT NULL DEFAULT 'seeding' CHECK (state IN ('seeding', 'ready', 'error')),
    base_version text CHECK (base_version ~ '^[0-9]+\.[0-9]+\.[0-9]+$'),
    detail       text CHECK (char_length(detail) <= 300),
    created_by   uuid REFERENCES auth.users(id) ON DELETE SET NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    UNIQUE (repo_id)
);
CREATE INDEX IF NOT EXISTS idx_agent_engine_repos_org ON public.agent_engine_repos (org_id);

-- A CI build of the engine repo's main branch, verified and copied into the private agent-engines bucket.
CREATE TABLE IF NOT EXISTS public.agent_engine_builds (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    agent_id     uuid NOT NULL REFERENCES public.agents(id) ON DELETE CASCADE,
    org_id       uuid NOT NULL REFERENCES public.organisations(id) ON DELETE CASCADE,
    repo         text NOT NULL CHECK (repo ~ '^[A-Za-z0-9-]+/[A-Za-z0-9._-]+$'),
    commit_sha   text NOT NULL CHECK (commit_sha ~ '^[0-9a-f]{40}$'),
    run_id       bigint NOT NULL CHECK (run_id > 0),
    run_url      text NOT NULL CHECK (run_url ~ '^https://github\.com/'),
    engine_version text NOT NULL CHECK (engine_version ~ '^[0-9]+\.[0-9]+\.[0-9]+$'),
    message      text NOT NULL DEFAULT '' CHECK (char_length(message) <= 200),
    jar_sha256   text NOT NULL CHECK (jar_sha256 ~ '^[0-9a-f]{64}$'),
    jar_size     bigint NOT NULL CHECK (jar_size > 0),
    storage_path text NOT NULL CHECK (storage_path !~ '(^/|(^|/)\.\.(/|$))'),
    imported_by  uuid REFERENCES auth.users(id) ON DELETE SET NULL,
    imported_at  timestamptz NOT NULL DEFAULT now(),
    UNIQUE (agent_id, jar_sha256),
    UNIQUE (agent_id, run_id)
);
CREATE INDEX IF NOT EXISTS idx_agent_engine_builds_agent ON public.agent_engine_builds (agent_id, imported_at DESC);

-- Which imported build a published adapter version bundles. Written once per (agent, version).
CREATE TABLE IF NOT EXISTS public.agent_version_engines (
    agent_id   uuid NOT NULL REFERENCES public.agents(id) ON DELETE CASCADE,
    version    text NOT NULL CHECK (version ~ '^[0-9]+\.[0-9]+\.[0-9]+$'),
    build_id   uuid NOT NULL REFERENCES public.agent_engine_builds(id) ON DELETE RESTRICT,
    created_by uuid REFERENCES auth.users(id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (agent_id, version)
);

-- The engine commit a pod reports running for an install (the provisioner bot writes it, like running_version).
ALTER TABLE public.agent_installs ADD COLUMN IF NOT EXISTS running_engine_commit text
    CHECK (running_engine_commit ~ '^[0-9a-f]{40}$');
GRANT UPDATE (running_engine_commit) ON public.agent_installs TO authenticated;

-- ----------------------------------------------------------------------------
-- 3. Row-level security: read with agents.view on the org; no client writes.
-- ----------------------------------------------------------------------------
ALTER TABLE public.org_github_connections ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.github_connect_states ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_engine_repos ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_engine_builds ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_version_engines ENABLE ROW LEVEL SECURITY;

REVOKE ALL ON public.org_github_connections, public.github_connect_states, public.agent_engine_repos,
    public.agent_engine_builds, public.agent_version_engines FROM PUBLIC, anon, authenticated;
GRANT ALL ON public.org_github_connections, public.github_connect_states, public.agent_engine_repos,
    public.agent_engine_builds, public.agent_version_engines TO service_role;
GRANT SELECT ON public.org_github_connections, public.agent_engine_repos, public.agent_engine_builds,
    public.agent_version_engines TO authenticated;

DROP POLICY IF EXISTS "org_github_connections view" ON public.org_github_connections;
CREATE POLICY "org_github_connections view" ON public.org_github_connections
    FOR SELECT TO authenticated USING ((SELECT public.has_org_permission(org_id, 'agents.view')));
DROP POLICY IF EXISTS "agent_engine_repos view" ON public.agent_engine_repos;
CREATE POLICY "agent_engine_repos view" ON public.agent_engine_repos
    FOR SELECT TO authenticated USING ((SELECT public.has_org_permission(org_id, 'agents.view')));
DROP POLICY IF EXISTS "agent_engine_builds view" ON public.agent_engine_builds;
CREATE POLICY "agent_engine_builds view" ON public.agent_engine_builds
    FOR SELECT TO authenticated USING ((SELECT public.has_org_permission(org_id, 'agents.view')));
DROP POLICY IF EXISTS "agent_version_engines view" ON public.agent_version_engines;
CREATE POLICY "agent_version_engines view" ON public.agent_version_engines
    FOR SELECT TO authenticated USING (EXISTS (
        SELECT 1 FROM public.agents a WHERE a.id = agent_id AND public.has_org_permission(a.org_id, 'agents.view')));

-- ----------------------------------------------------------------------------
-- 4. Audit: connection, repo and build changes land in agent_audit_log (append-only).
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.agent_engine_audit() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    actor uuid := public.agent_audit_actor();
BEGIN
    IF TG_TABLE_NAME = 'org_github_connections' THEN
        IF TG_OP = 'INSERT' OR (TG_OP = 'UPDATE' AND (OLD.status IS DISTINCT FROM NEW.status
                                                     OR OLD.installation_id IS DISTINCT FROM NEW.installation_id)) THEN
            INSERT INTO public.agent_audit_log (org_id, agent_id, actor, action, detail)
                VALUES (NEW.org_id, NULL, COALESCE(actor, NEW.connected_by),
                        CASE WHEN NEW.status = 'active' THEN 'github.connect' ELSE 'github.disconnect' END,
                        jsonb_build_object('account', NEW.account_login, 'installation_id', NEW.installation_id));
        END IF;
    ELSIF TG_TABLE_NAME = 'agent_engine_repos' THEN
        IF TG_OP = 'INSERT' THEN
            INSERT INTO public.agent_audit_log (org_id, agent_id, actor, action, detail)
                VALUES (NEW.org_id, NEW.agent_id, COALESCE(actor, NEW.created_by), 'engine.repo_create',
                        jsonb_build_object('repo', NEW.repo_owner || '/' || NEW.repo_name));
        ELSIF OLD.state IS DISTINCT FROM NEW.state THEN
            INSERT INTO public.agent_audit_log (org_id, agent_id, actor, action, detail)
                VALUES (NEW.org_id, NEW.agent_id, actor, 'engine.repo_state',
                        jsonb_build_object('repo', NEW.repo_owner || '/' || NEW.repo_name, 'from', OLD.state, 'to', NEW.state));
        END IF;
    ELSIF TG_TABLE_NAME = 'agent_engine_builds' THEN
        INSERT INTO public.agent_audit_log (org_id, agent_id, actor, action, detail)
            VALUES (NEW.org_id, NEW.agent_id, COALESCE(actor, NEW.imported_by), 'engine.build_import',
                    jsonb_build_object('repo', NEW.repo, 'commit', NEW.commit_sha, 'run_id', NEW.run_id, 'sha256', NEW.jar_sha256));
    END IF;
    RETURN NULL;
END;
$$;
REVOKE ALL ON FUNCTION public.agent_engine_audit() FROM PUBLIC, anon, authenticated;

DROP TRIGGER IF EXISTS org_github_connections_audit ON public.org_github_connections;
CREATE TRIGGER org_github_connections_audit AFTER INSERT OR UPDATE ON public.org_github_connections
    FOR EACH ROW EXECUTE FUNCTION public.agent_engine_audit();
DROP TRIGGER IF EXISTS agent_engine_repos_audit ON public.agent_engine_repos;
CREATE TRIGGER agent_engine_repos_audit AFTER INSERT OR UPDATE ON public.agent_engine_repos
    FOR EACH ROW EXECUTE FUNCTION public.agent_engine_audit();
DROP TRIGGER IF EXISTS agent_engine_builds_audit ON public.agent_engine_builds;
CREATE TRIGGER agent_engine_builds_audit AFTER INSERT ON public.agent_engine_builds
    FOR EACH ROW EXECUTE FUNCTION public.agent_engine_audit();

CREATE OR REPLACE FUNCTION public.agent_engine_touch() RETURNS trigger
    LANGUAGE plpgsql SET search_path = '' AS $$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END;
$$;
DROP TRIGGER IF EXISTS org_github_connections_touch ON public.org_github_connections;
CREATE TRIGGER org_github_connections_touch BEFORE UPDATE ON public.org_github_connections
    FOR EACH ROW EXECUTE FUNCTION public.agent_engine_touch();
DROP TRIGGER IF EXISTS agent_engine_repos_touch ON public.agent_engine_repos;
CREATE TRIGGER agent_engine_repos_touch BEFORE UPDATE ON public.agent_engine_repos
    FOR EACH ROW EXECUTE FUNCTION public.agent_engine_touch();

-- ----------------------------------------------------------------------------
-- 5. RPCs.
-- ----------------------------------------------------------------------------
-- The console records which build a version bundles, after the store finalized the version and before
-- publish_agent. Once per (agent, version): the same build again is a no-op, another build is refused.
CREATE OR REPLACE FUNCTION public.record_agent_version_engine(p_agent_id uuid, p_version text, p_build_id uuid)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    a public.agents%ROWTYPE;
    b public.agent_engine_builds%ROWTYPE;
    have uuid;
BEGIN
    IF auth.uid() IS NULL THEN
        RAISE EXCEPTION 'Not signed in' USING ERRCODE = '42501';
    END IF;
    SELECT * INTO a FROM public.agents WHERE id = p_agent_id;
    IF NOT FOUND OR NOT public.has_org_permission(a.org_id, 'agents.publish') THEN
        RAISE EXCEPTION 'Permission denied' USING ERRCODE = '42501';
    END IF;
    IF p_version IS NULL OR p_version !~ '^[0-9]+\.[0-9]+\.[0-9]+$' THEN
        RAISE EXCEPTION 'invalid_version' USING ERRCODE = 'P0001';
    END IF;
    SELECT * INTO b FROM public.agent_engine_builds WHERE id = p_build_id;
    IF NOT FOUND OR b.agent_id <> p_agent_id THEN
        RAISE EXCEPTION 'build_not_found' USING ERRCODE = 'P0001';
    END IF;
    SELECT build_id INTO have FROM public.agent_version_engines WHERE agent_id = p_agent_id AND version = p_version;
    IF FOUND THEN
        IF have = p_build_id THEN RETURN; END IF;
        RAISE EXCEPTION 'version_engine_set' USING ERRCODE = 'P0001';
    END IF;
    INSERT INTO public.agent_version_engines (agent_id, version, build_id, created_by)
        VALUES (p_agent_id, p_version, p_build_id, auth.uid());
    INSERT INTO public.agent_audit_log (org_id, agent_id, actor, action, detail)
        VALUES (a.org_id, a.id, auth.uid(), 'engine.version_pin',
                jsonb_build_object('version', p_version, 'repo', b.repo, 'commit', b.commit_sha, 'sha256', b.jar_sha256));
END;
$$;
REVOKE ALL ON FUNCTION public.record_agent_version_engine(uuid, text, uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.record_agent_version_engine(uuid, text, uuid) TO authenticated;

-- The provisioner bot: the engine pin of each agent's LIVE version (agents without one are absent).
CREATE OR REPLACE FUNCTION public.agent_live_engines(p_agent_ids uuid[])
RETURNS TABLE (agent_id uuid, version text, repo text, commit_sha text, jar_sha256 text)
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
BEGIN
    IF NOT public.is_agents_provisioner() THEN
        RAISE EXCEPTION 'Permission denied' USING ERRCODE = '42501';
    END IF;
    IF p_agent_ids IS NULL OR cardinality(p_agent_ids) > 1000 THEN
        RAISE EXCEPTION 'invalid_argument' USING ERRCODE = '22023';
    END IF;
    RETURN QUERY
        SELECT a.id, a.live_version, b.repo, b.commit_sha, b.jar_sha256
        FROM public.agents a
        JOIN public.agent_engine_repos r ON r.agent_id = a.id
        LEFT JOIN public.agent_version_engines v ON v.agent_id = a.id AND v.version = a.live_version
        LEFT JOIN public.agent_engine_builds b ON b.id = v.build_id
        WHERE a.id = ANY (p_agent_ids) AND a.status = 'live';
END;
$$;
REVOKE ALL ON FUNCTION public.agent_live_engines(uuid[]) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.agent_live_engines(uuid[]) TO authenticated;

-- The console's Engine tab in one read: connection, repo, recent builds, which version pins which build and
-- how many installs run each commit.
CREATE OR REPLACE FUNCTION public.agent_engine_overview(p_agent_id uuid) RETURNS jsonb
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    a public.agents%ROWTYPE;
BEGIN
    SELECT * INTO a FROM public.agents WHERE id = p_agent_id;
    IF NOT FOUND OR NOT public.has_org_permission(a.org_id, 'agents.view') THEN
        RAISE EXCEPTION 'Permission denied' USING ERRCODE = '42501';
    END IF;
    RETURN jsonb_build_object(
        'connection', (SELECT jsonb_build_object('account', c.account_login, 'status', c.status, 'connected_at', c.connected_at)
                       FROM public.org_github_connections c WHERE c.org_id = a.org_id),
        'repo', (SELECT jsonb_build_object('owner', r.repo_owner, 'name', r.repo_name, 'state', r.state,
                                           'base_version', r.base_version, 'detail', r.detail, 'created_at', r.created_at)
                 FROM public.agent_engine_repos r WHERE r.agent_id = a.id),
        'builds', COALESCE((SELECT jsonb_agg(jsonb_build_object('id', b.id, 'commit', b.commit_sha, 'run_id', b.run_id,
                                    'run_url', b.run_url, 'engine_version', b.engine_version, 'message', b.message,
                                    'sha256', b.jar_sha256, 'size', b.jar_size, 'imported_at', b.imported_at)
                                    ORDER BY b.imported_at DESC)
                            FROM (SELECT * FROM public.agent_engine_builds WHERE agent_id = a.id
                                  ORDER BY imported_at DESC LIMIT 50) b), '[]'::jsonb),
        'versions', COALESCE((SELECT jsonb_agg(jsonb_build_object('version', v.version, 'build_id', v.build_id,
                                      'commit', b.commit_sha) ORDER BY v.created_at DESC)
                              FROM public.agent_version_engines v JOIN public.agent_engine_builds b ON b.id = v.build_id
                              WHERE v.agent_id = a.id), '[]'::jsonb),
        'running', COALESCE((SELECT jsonb_object_agg(x.commit, x.n) FROM (
                                SELECT COALESCE(i.running_engine_commit, 'unknown') AS commit, count(*) AS n
                                FROM public.agent_installs i WHERE i.agent_id = a.id AND i.state <> 'removed'
                                GROUP BY 1) x), '{}'::jsonb)
    );
END;
$$;
REVOKE ALL ON FUNCTION public.agent_engine_overview(uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.agent_engine_overview(uuid) TO authenticated;

-- ----------------------------------------------------------------------------
-- 6. Storage: imported engine jars, private. Only the edge function (service role) reads or writes.
-- ----------------------------------------------------------------------------
INSERT INTO storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
VALUES ('agent-engines', 'agent-engines', false, 104857600, ARRAY['application/java-archive', 'application/octet-stream'])
ON CONFLICT (id) DO NOTHING;

-- A custom engine cannot silently become stock through a direct publish_agent call.
-- Existing live stock versions survive repository setup, until the first new publication.
CREATE OR REPLACE FUNCTION public.require_agent_engine_pin() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM public.agent_engine_repos r WHERE r.agent_id = NEW.id) THEN
        IF (NEW.live_version IS DISTINCT FROM OLD.live_version AND NEW.live_version IS NOT NULL
            AND NOT EXISTS (SELECT 1 FROM public.agent_version_engines v WHERE v.agent_id = NEW.id AND v.version = NEW.live_version))
          OR (NEW.pending_version IS DISTINCT FROM OLD.pending_version AND NEW.pending_version IS NOT NULL
            AND NOT EXISTS (SELECT 1 FROM public.agent_version_engines v WHERE v.agent_id = NEW.id AND v.version = NEW.pending_version)) THEN
            RAISE EXCEPTION 'engine_pin_required' USING ERRCODE = 'P0001';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION public.require_agent_engine_pin() FROM PUBLIC, anon, authenticated;
CREATE TRIGGER require_agent_engine_pin BEFORE UPDATE OF live_version, pending_version ON public.agents
FOR EACH ROW EXECUTE FUNCTION public.require_agent_engine_pin();
