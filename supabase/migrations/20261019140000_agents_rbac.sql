-- ============================================================================
-- Agents platform: org-scoped agents.* permissions on the existing organisation role system,
-- built-in agent roles per org, Team RPCs and audit entries for role changes.
-- ============================================================================
-- Builds on 20260801010000 (org predicates and guards), 20260801030000 (org role RPCs),
-- 20260801040000 (invites), 20261016130000 and 20261019120000 (agents platform).
--
-- The decision is user_has_org_permission(user, org, perm): an org admin (user_is_org_admin: owner,
-- global admin, or an active holder of the org's admin-kind role) holds every agents.* permission;
-- anyone else must be an ACTIVE member holding a role of THAT org whose closure, restricted to roles
-- of the same org, carries the permission. The restriction is what keeps it org-scoped: the global
-- `user` role sits below every org's user role, and authorize()/get_effective_permissions answer
-- "anywhere", so neither may be used here. Any agents.* permission implies agents.view.
-- It reads the tables on every call, so a revocation applies to the next statement.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. Catalog. System permissions, so no plugin can register or re-own them, and the agents domain is
--    reserved against plugin-defined lookalikes (agents.admin, ...).
-- ----------------------------------------------------------------------------
INSERT INTO public.permissions (name, description, is_system) VALUES
    ('agents.view', 'Agents console read-only: agents, settings, overview, people, usage, audit log (aggregates only)', true),
    ('agents.edit', 'Create agents; edit brand, sign-in page, persona, tools, connectors and model', true),
    ('agents.publish', 'Publish agent versions (store, publish_agent, Max "Publish to my org")', true),
    ('agents.evolve', 'Max self-evolve in agent mode', true),
    ('agents.access', 'Agent access policy, invites and removing a person''s install', true),
    ('agents.domains', 'Add, verify and delete agent custom domains', true)
ON CONFLICT (name) DO UPDATE SET is_system = true, description = EXCLUDED.description;

CREATE OR REPLACE FUNCTION public.agents_permission_catalog() RETURNS text[]
    LANGUAGE sql IMMUTABLE SET search_path = '' AS $$
    SELECT ARRAY['agents.view', 'agents.edit', 'agents.publish', 'agents.evolve', 'agents.access', 'agents.domains'];
$$;
REVOKE ALL ON FUNCTION public.agents_permission_catalog() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.agents_permission_catalog() TO authenticated, service_role;

-- Body identical to 20260801010000 apart from the reserved-domain list ('agents' added).
CREATE OR REPLACE FUNCTION "public"."register_plugin_permission"(
    "p_name" "text",
    "p_description" "text" DEFAULT NULL::"text",
    "p_plugin_id" "text" DEFAULT NULL::"text"
) RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $_$
DECLARE
    v_domain TEXT;
    v_existing_id UUID;
    v_existing_is_system BOOLEAN;
    v_permission_id UUID;
BEGIN
    IF p_name IS NULL OR NOT (p_name ~ '^[a-z][a-z0-9_]{1,30}\.[a-z][a-z0-9_]{1,30}$') THEN
        RETURN jsonb_build_object('success', false, 'error', 'Invalid permission format');
    END IF;

    v_domain := split_part(p_name, '.', 1);
    IF v_domain IN ('role', 'user', 'api_key', 'rpa', 'secret', 'plugins', 'organisation', 'org', 'agents') THEN
        RETURN jsonb_build_object('success', false, 'error',
            format('Reserved permission domain "%s"', v_domain));
    END IF;

    SELECT id, is_system INTO v_existing_id, v_existing_is_system
    FROM public.permissions WHERE name = p_name;

    IF FOUND THEN
        IF v_existing_is_system THEN
            RETURN jsonb_build_object('success', false, 'error',
                format('Permission "%s" is a system permission', p_name));
        END IF;
        INSERT INTO public.plugin_permissions (permission_id, plugin_id)
        VALUES (v_existing_id, p_plugin_id)
        ON CONFLICT (permission_id) DO NOTHING;
        RETURN jsonb_build_object('success', true, 'created', false,
            'permission_id', v_existing_id::text, 'permission', p_name);
    END IF;

    INSERT INTO public.permissions (name, description, is_system)
    VALUES (p_name, p_description, false)
    RETURNING id INTO v_permission_id;

    INSERT INTO public.plugin_permissions (permission_id, plugin_id)
    VALUES (v_permission_id, p_plugin_id)
    ON CONFLICT (permission_id) DO NOTHING;

    RETURN jsonb_build_object('success', true, 'created', true,
        'permission_id', v_permission_id::text, 'permission', p_name);
END;
$_$;
REVOKE EXECUTE ON FUNCTION "public"."register_plugin_permission"("text", "text", "text") FROM PUBLIC, "anon", "authenticated";
GRANT EXECUTE ON FUNCTION "public"."register_plugin_permission"("text", "text", "text") TO "service_role";

-- ----------------------------------------------------------------------------
-- 2. The check
-- ----------------------------------------------------------------------------
-- Only agents.* is answered; anything else is false (fail closed), so the admin short-circuit can
-- never be read as "an org admin holds ai.* in their org".
CREATE OR REPLACE FUNCTION public.user_has_org_permission(p_user_id uuid, p_org_id uuid, p_permission text)
RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
    SELECT COALESCE(p_user_id IS NOT NULL AND p_org_id IS NOT NULL
       AND p_permission = ANY (public.agents_permission_catalog())
       AND (
           public.user_is_org_admin(p_user_id, p_org_id)
           OR (
               EXISTS (SELECT 1 FROM public.organisation_members m
                       WHERE m.org_id = p_org_id AND m.user_id = p_user_id AND m.status = 'active')
               AND EXISTS (
                   SELECT 1
                   FROM public.user_roles ur
                   JOIN public.organisation_roles orl ON orl.role_id = ur.role_id AND orl.org_id = p_org_id
                   CROSS JOIN LATERAL public.get_role_descendants(ur.role_id) AS d(role_id)
                   JOIN public.organisation_roles dorl ON dorl.role_id = d.role_id AND dorl.org_id = p_org_id
                   JOIN public.role_permissions rp ON rp.role_id = d.role_id
                   JOIN public.permissions p ON p.id = rp.permission_id
                   WHERE ur.user_id = p_user_id
                     AND (p.name = p_permission
                          OR (p_permission = 'agents.view' AND p.name = ANY (public.agents_permission_catalog())))
               )
           )
       ), false);
$$;
REVOKE ALL ON FUNCTION public.user_has_org_permission(uuid, uuid, text) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.user_has_org_permission(uuid, uuid, text) TO service_role, supabase_auth_admin;

CREATE OR REPLACE FUNCTION public.has_org_permission(p_org_id uuid, p_permission text) RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
    SELECT public.user_has_org_permission(auth.uid(), p_org_id, p_permission);
$$;
REVOKE ALL ON FUNCTION public.has_org_permission(uuid, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.has_org_permission(uuid, text) TO authenticated, service_role;

CREATE OR REPLACE FUNCTION public.user_has_agent_permission(p_user_id uuid, p_agent_id uuid, p_permission text)
RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
    SELECT EXISTS (SELECT 1 FROM public.agents a
                   WHERE a.id = p_agent_id AND public.user_has_org_permission(p_user_id, a.org_id, p_permission));
$$;
REVOKE ALL ON FUNCTION public.user_has_agent_permission(uuid, uuid, text) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.user_has_agent_permission(uuid, uuid, text) TO service_role;

CREATE OR REPLACE FUNCTION public.has_agent_permission(p_agent_id uuid, p_permission text) RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
    SELECT public.user_has_agent_permission(auth.uid(), p_agent_id, p_permission);
$$;
REVOKE ALL ON FUNCTION public.has_agent_permission(uuid, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.has_agent_permission(uuid, text) TO authenticated, service_role;

-- The caller's own agents.* permissions in an org, in catalog order, plus 'organisation.admin' when
-- they administer it (team management). Empty for a non-member.
CREATE OR REPLACE FUNCTION public.my_org_permissions(p_org_id uuid) RETURNS text[]
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
    SELECT COALESCE(ARRAY(
        SELECT c.perm FROM unnest(public.agents_permission_catalog()) WITH ORDINALITY AS c(perm, n)
        WHERE public.user_has_org_permission(auth.uid(), p_org_id, c.perm)
        ORDER BY c.n), ARRAY[]::text[])
        || CASE WHEN auth.uid() IS NOT NULL AND public.user_is_org_admin(auth.uid(), p_org_id)
                THEN ARRAY['organisation.admin'] ELSE ARRAY[]::text[] END;
$$;
REVOKE ALL ON FUNCTION public.my_org_permissions(uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.my_org_permissions(uuid) TO authenticated, service_role;

-- ----------------------------------------------------------------------------
-- 3. Built-in roles: <slug>_agent_manager / _agent_editor / _agent_analyst, kind 'custom' (editable and
--    deletable like any custom role), marked by organisation_roles.preset. The org's admin-kind role
--    carries every agents.* permission directly, so "cannot grant what you do not hold" in
--    grant_organisation_role_permission keeps working for org admins.
-- ----------------------------------------------------------------------------
ALTER TABLE public.organisation_roles ADD COLUMN IF NOT EXISTS preset text
    CHECK (preset IN ('agent_manager', 'agent_editor', 'agent_analyst'));
CREATE UNIQUE INDEX IF NOT EXISTS idx_organisation_roles_one_per_preset
    ON public.organisation_roles (org_id, preset) WHERE preset IS NOT NULL;
COMMENT ON COLUMN public.organisation_roles.preset IS
    'Built-in agent role this custom role was seeded as (agent_manager | agent_editor | agent_analyst). Display only; permissions stay editable. Not counted against max_custom_roles.';

CREATE OR REPLACE FUNCTION public.seed_org_agent_roles(p_org_id uuid) RETURNS void
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    v_slug text;
    v_org_name text;
    v_admin uuid;
    v_user uuid;
    v_role uuid;
    v_name text;
    r record;
BEGIN
    SELECT o.slug, o.name INTO v_slug, v_org_name FROM public.organisations o WHERE o.id = p_org_id;
    IF NOT FOUND THEN
        RETURN;
    END IF;
    PERFORM pg_catalog.set_config('agents.audit_suppress', 'on', true);
    SELECT orl.role_id INTO v_admin FROM public.organisation_roles orl WHERE orl.org_id = p_org_id AND orl.kind = 'admin';
    SELECT orl.role_id INTO v_user FROM public.organisation_roles orl WHERE orl.org_id = p_org_id AND orl.kind = 'user';

    IF v_admin IS NOT NULL THEN
        INSERT INTO public.role_permissions (role_id, permission_id)
            SELECT v_admin, p.id FROM public.permissions p WHERE p.name = ANY (public.agents_permission_catalog())
            ON CONFLICT (role_id, permission_id) DO NOTHING;
    END IF;

    FOR r IN SELECT * FROM (VALUES
        ('agent_manager', 'Agent manager: view, edit, publish, access and domains',
         ARRAY['agents.view', 'agents.edit', 'agents.publish', 'agents.access', 'agents.domains']),
        ('agent_editor', 'Agent editor: view and edit agents', ARRAY['agents.view', 'agents.edit']),
        ('agent_analyst', 'Agent analyst: read-only agents console', ARRAY['agents.view'])
    ) AS t(preset, description, perms) LOOP
        CONTINUE WHEN EXISTS (SELECT 1 FROM public.organisation_roles orl
                              WHERE orl.org_id = p_org_id AND orl.preset = r.preset);
        v_name := public.organisation_role_name(v_slug, r.preset);
        IF pg_catalog.char_length(v_name) > 50 OR EXISTS (SELECT 1 FROM public.roles x WHERE x.name = v_name) THEN
            RAISE NOTICE 'seed_org_agent_roles: skipped % for org % (name taken or too long)', v_name, p_org_id;
            CONTINUE;
        END IF;
        INSERT INTO public.roles (name, description, is_system) VALUES (v_name, r.description, false)
            RETURNING id INTO v_role;
        INSERT INTO public.organisation_roles (org_id, role_id, kind, created_by, preset)
            VALUES (p_org_id, v_role, 'custom', NULL, r.preset);
        IF v_admin IS NOT NULL THEN
            INSERT INTO public.role_hierarchy (parent_role_id, child_role_id) VALUES (v_admin, v_role) ON CONFLICT DO NOTHING;
        END IF;
        IF v_user IS NOT NULL THEN
            INSERT INTO public.role_hierarchy (parent_role_id, child_role_id) VALUES (v_role, v_user) ON CONFLICT DO NOTHING;
        END IF;
        INSERT INTO public.role_permissions (role_id, permission_id)
            SELECT v_role, p.id FROM public.permissions p WHERE p.name = ANY (r.perms || ARRAY['organisation.read'])
            ON CONFLICT (role_id, permission_id) DO NOTHING;
    END LOOP;

    -- A preset seeded before the admin/user role existed gets its edges now.
    IF v_admin IS NOT NULL THEN
        INSERT INTO public.role_hierarchy (parent_role_id, child_role_id)
            SELECT v_admin, orl.role_id FROM public.organisation_roles orl
            WHERE orl.org_id = p_org_id AND orl.preset IS NOT NULL ON CONFLICT DO NOTHING;
    END IF;
    IF v_user IS NOT NULL THEN
        INSERT INTO public.role_hierarchy (parent_role_id, child_role_id)
            SELECT orl.role_id, v_user FROM public.organisation_roles orl
            WHERE orl.org_id = p_org_id AND orl.preset IS NOT NULL ON CONFLICT DO NOTHING;
    END IF;
    PERFORM pg_catalog.set_config('agents.audit_suppress', '', true);
END;
$$;
REVOKE ALL ON FUNCTION public.seed_org_agent_roles(uuid) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.seed_org_agent_roles(uuid) TO service_role;

-- New orgs: seeded when their admin/user role is mapped (create_organisation_internal maps both in
-- one statement; the seed is idempotent, so firing for each is harmless).
CREATE OR REPLACE FUNCTION public.organisation_roles_seed_agents() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
BEGIN
    PERFORM public.seed_org_agent_roles(NEW.org_id);
    RETURN NULL;
END;
$$;
REVOKE ALL ON FUNCTION public.organisation_roles_seed_agents() FROM PUBLIC, anon, authenticated;
DROP TRIGGER IF EXISTS organisation_roles_seed_agents ON public.organisation_roles;
CREATE TRIGGER organisation_roles_seed_agents AFTER INSERT ON public.organisation_roles
    FOR EACH ROW WHEN (NEW.kind IN ('admin', 'user')) EXECUTE FUNCTION public.organisation_roles_seed_agents();

-- Existing orgs.
DO $$
DECLARE o record;
BEGIN
    FOR o IN SELECT id FROM public.organisations LOOP
        PERFORM public.seed_org_agent_roles(o.id);
    END LOOP;
END $$;

-- create_organisation_role: identical to 20260801030000 except built-in (preset) roles do not count
-- against max_custom_roles.
CREATE OR REPLACE FUNCTION "public"."create_organisation_role"(
    "p_org_id" "uuid",
    "p_suffix" "text",
    "p_description" "text" DEFAULT NULL::"text",
    "p_actor_id" "uuid" DEFAULT NULL::"uuid"
) RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
DECLARE
    v_actor UUID;
    v_slug TEXT;
    v_max INTEGER;
    v_custom_count INTEGER;
    v_name TEXT;
    v_role_id UUID;
    v_admin_role_id UUID;
    v_user_role_id UUID;
BEGIN
    v_actor := public.resolve_org_actor(p_actor_id);
    IF v_actor IS NULL THEN
        RETURN jsonb_build_object('success', false, 'error', 'Not authenticated');
    END IF;

    IF NOT public.user_is_org_admin(v_actor, p_org_id) THEN
        RETURN jsonb_build_object('success', false, 'error', 'Permission denied');
    END IF;

    SELECT o.slug, o.max_custom_roles INTO v_slug, v_max
    FROM public.organisations o WHERE o.id = p_org_id;
    IF NOT FOUND THEN
        RETURN jsonb_build_object('success', false, 'error', 'Organisation not found');
    END IF;

    IF p_suffix IS NULL OR NOT (p_suffix ~ '^[a-z][a-z0-9_]{1,19}$') THEN
        RETURN jsonb_build_object('success', false, 'error',
            'Role suffix must be 2-20 characters, lowercase letters, digits and underscores, starting with a letter');
    END IF;

    IF p_suffix IN ('admin', 'user') THEN
        RETURN jsonb_build_object('success', false, 'error',
            '"admin" and "user" are reserved -- the organisation already has those roles');
    END IF;

    v_name := public.organisation_role_name(v_slug, p_suffix);

    IF char_length(v_name) < 3 OR char_length(v_name) > 50 THEN
        RETURN jsonb_build_object('success', false, 'error',
            format('The resulting role name "%s" must be 3-50 characters', v_name));
    END IF;

    IF EXISTS (SELECT 1 FROM public.roles r WHERE r.name = v_name) THEN
        RETURN jsonb_build_object('success', false, 'error',
            format('A role named "%s" already exists', v_name));
    END IF;

    SELECT count(*) INTO v_custom_count
    FROM public.organisation_roles orl
    WHERE orl.org_id = p_org_id AND orl.kind = 'custom' AND orl.preset IS NULL;

    IF v_custom_count >= COALESCE(v_max, 25) THEN
        RETURN jsonb_build_object('success', false, 'error',
            format('This organisation has reached its limit of %s custom roles', COALESCE(v_max, 25)));
    END IF;

    INSERT INTO public.roles (name, description, is_system)
    VALUES (v_name, COALESCE(p_description, format('Custom role of the "%s" organisation', v_slug)), false)
    RETURNING id INTO v_role_id;

    INSERT INTO public.organisation_roles (org_id, role_id, kind, created_by)
    VALUES (p_org_id, v_role_id, 'custom', v_actor);

    SELECT orl.role_id INTO v_admin_role_id
    FROM public.organisation_roles orl WHERE orl.org_id = p_org_id AND orl.kind = 'admin';
    SELECT orl.role_id INTO v_user_role_id
    FROM public.organisation_roles orl WHERE orl.org_id = p_org_id AND orl.kind = 'user';

    IF v_admin_role_id IS NOT NULL THEN
        INSERT INTO public.role_hierarchy (parent_role_id, child_role_id)
        VALUES (v_admin_role_id, v_role_id) ON CONFLICT DO NOTHING;
    END IF;
    IF v_user_role_id IS NOT NULL THEN
        INSERT INTO public.role_hierarchy (parent_role_id, child_role_id)
        VALUES (v_role_id, v_user_role_id) ON CONFLICT DO NOTHING;
    END IF;

    INSERT INTO public.role_permissions (role_id, permission_id)
    SELECT v_role_id, p.id FROM public.permissions p WHERE p.name = 'organisation.read'
    ON CONFLICT (role_id, permission_id) DO NOTHING;

    RETURN jsonb_build_object('success', true, 'role_id', v_role_id::text, 'role_name', v_name);
END;
$$;
ALTER FUNCTION "public"."create_organisation_role"("uuid", "text", "text", "uuid") OWNER TO "postgres";
REVOKE EXECUTE ON FUNCTION "public"."create_organisation_role"("uuid", "text", "text", "uuid") FROM PUBLIC, "anon";
GRANT EXECUTE ON FUNCTION "public"."create_organisation_role"("uuid", "text", "text", "uuid") TO "authenticated", "service_role";

-- get_organisation_detail: identical to 20260807000000 except custom_role_count, which sits next to
-- max_custom_roles, leaves the built-in roles out as create_organisation_role now does.
CREATE OR REPLACE FUNCTION "public"."get_organisation_detail"(
    "p_org_id" "uuid",
    "p_actor_id" "uuid" DEFAULT NULL::"uuid"
) RETURNS "jsonb"
    LANGUAGE "plpgsql" STABLE SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
DECLARE
    v_actor UUID;
    v_is_admin BOOLEAN;
    v_org public.organisations%ROWTYPE;
    v_data JSONB;
BEGIN
    v_actor := public.resolve_org_actor(p_actor_id);
    IF v_actor IS NULL THEN
        RETURN jsonb_build_object('success', false, 'error', 'Not authenticated');
    END IF;

    IF NOT public.user_is_org_member(v_actor, p_org_id) THEN
        RETURN jsonb_build_object('success', false, 'error', 'Organisation not found');
    END IF;

    SELECT * INTO v_org FROM public.organisations o WHERE o.id = p_org_id;
    IF NOT FOUND THEN
        RETURN jsonb_build_object('success', false, 'error', 'Organisation not found');
    END IF;

    v_is_admin := public.user_is_org_admin(v_actor, p_org_id);

    -- Member-visible projection. owner_id is included because the page renders
    -- "owned by <email>", and ownership is not a secret from the membership.
    v_data := jsonb_build_object(
        'id',           v_org.id::text,
        'slug',         v_org.slug,
        'name',         v_org.name,
        'description',  v_org.description,
        'website',      v_org.website,
        'visibility',   v_org.visibility,
        'join_policy',  v_org.join_policy,
        'is_system',    v_org.is_system,
        'created_at',   v_org.created_at,
        'owner_id',     v_org.owner_id::text,
        'owner_email',  (SELECT u.email FROM auth.users u WHERE u.id = v_org.owner_id),
        'member_count', (SELECT count(*) FROM public.organisation_members m
                          WHERE m.org_id = p_org_id AND m.status = 'active'),
        'pending_count', (SELECT count(*) FROM public.organisation_members m
                           WHERE m.org_id = p_org_id AND m.status = 'pending'),
        'primary_domain', (SELECT d.domain FROM public.organisation_domains d
                            WHERE d.org_id = p_org_id AND d.is_primary AND d.verified LIMIT 1),
        'is_owner',     (v_org.owner_id = v_actor),
        'is_admin',     v_is_admin,
        'can_publish',  public.user_can_publish_org_plugin(v_actor, p_org_id));

    -- Admin-only settings. Merged in rather than emitted as NULLs: a member's
    -- response simply does not carry these keys.
    IF v_is_admin THEN
        v_data := v_data || jsonb_build_object(
            'publish_policy',          v_org.publish_policy,
            'publish_role_id',         v_org.publish_role_id::text,
            'publish_role_name',       (SELECT r.name FROM public.roles r
                                         WHERE r.id = v_org.publish_role_id),
            'auto_assign_member_role', v_org.auto_assign_member_role,
            'max_custom_roles',        v_org.max_custom_roles,
            'custom_role_count',       (SELECT count(*) FROM public.organisation_roles orl
                                         WHERE orl.org_id = p_org_id AND orl.kind = 'custom' AND orl.preset IS NULL),
            'plugin_count',            (SELECT count(*) FROM public.plugins p
                                         WHERE p.org_id = p_org_id));
    END IF;

    RETURN jsonb_build_object('success', true, 'data', v_data);
END;
$$;

ALTER FUNCTION "public"."get_organisation_detail"("uuid", "uuid") OWNER TO "postgres";

-- ----------------------------------------------------------------------------
-- 4. Agents platform gates: is_org_admin -> the matching permission
-- ----------------------------------------------------------------------------
-- Kept for compatibility; nothing in the agents platform gates on it any more.
COMMENT ON FUNCTION public.is_agent_org_admin(uuid) IS
    'Org admin of the agent''s org. Superseded by has_agent_permission(agent, perm) for every agents gate.';

DROP POLICY IF EXISTS "agents org admin select" ON public.agents;
DROP POLICY IF EXISTS "agents org admin insert" ON public.agents;
DROP POLICY IF EXISTS "agents org admin update" ON public.agents;
DROP POLICY IF EXISTS "agents view" ON public.agents;
CREATE POLICY "agents view" ON public.agents
    FOR SELECT TO authenticated USING (public.has_org_permission(org_id, 'agents.view'));
DROP POLICY IF EXISTS "agents create" ON public.agents;
CREATE POLICY "agents create" ON public.agents
    FOR INSERT TO authenticated
    WITH CHECK (public.has_org_permission(org_id, 'agents.edit') AND created_by = (SELECT auth.uid()));
DROP POLICY IF EXISTS "agents update" ON public.agents;
CREATE POLICY "agents update" ON public.agents
    FOR UPDATE TO authenticated
    USING (public.has_org_permission(org_id, 'agents.edit') OR public.has_org_permission(org_id, 'agents.access'))
    WITH CHECK (public.has_org_permission(org_id, 'agents.edit') OR public.has_org_permission(org_id, 'agents.access'));

-- Column split RLS cannot express: signup_policy is access policy (agents.access); everything else a
-- client may write is agents.edit. Definer RPCs (publish_agent, review, suspend) never touch these
-- columns; writes with no user (service role) are not gated here.
CREATE OR REPLACE FUNCTION public.agents_permission_check() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    uid uuid := auth.uid();
BEGIN
    IF uid IS NULL THEN
        RETURN NEW;
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.signup_policy IS DISTINCT FROM '{"mode":"open"}'::jsonb
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
DROP TRIGGER IF EXISTS agents_permission_check_on_write ON public.agents;
CREATE TRIGGER agents_permission_check_on_write BEFORE INSERT OR UPDATE ON public.agents
    FOR EACH ROW EXECUTE FUNCTION public.agents_permission_check();

DROP POLICY IF EXISTS "agent_invites admin all" ON public.agent_invites;
DROP POLICY IF EXISTS "agent_invites view" ON public.agent_invites;
CREATE POLICY "agent_invites view" ON public.agent_invites
    FOR SELECT TO authenticated USING (public.has_agent_permission(agent_id, 'agents.view'));
DROP POLICY IF EXISTS "agent_invites access insert" ON public.agent_invites;
CREATE POLICY "agent_invites access insert" ON public.agent_invites
    FOR INSERT TO authenticated WITH CHECK (public.has_agent_permission(agent_id, 'agents.access'));
DROP POLICY IF EXISTS "agent_invites access delete" ON public.agent_invites;
CREATE POLICY "agent_invites access delete" ON public.agent_invites
    FOR DELETE TO authenticated USING (public.has_agent_permission(agent_id, 'agents.access'));

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
    IF NOT FOUND OR NOT public.has_org_permission(a.org_id, 'agents.publish') THEN
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

-- Brand bucket: <orgId>/<agentId|org>/<file>, writable with agents.edit on that org.
CREATE OR REPLACE FUNCTION public.is_agent_folder_admin(p_name text) RETURNS boolean
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    f text[] := storage.foldername(p_name);
    uuid_re constant text := '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$';
BEGIN
    IF pg_catalog.cardinality(f) <> 2 OR f[1] !~ uuid_re
       OR pg_catalog.split_part(p_name, '/', 3) !~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$' THEN
        RETURN false;
    END IF;
    IF NOT public.has_org_permission(f[1]::uuid, 'agents.edit') THEN
        RETURN false;
    END IF;
    RETURN f[2] = 'org'
        OR (f[2] ~ uuid_re AND EXISTS (SELECT 1 FROM public.agents a
                                       WHERE a.id = f[2]::uuid AND a.org_id = f[1]::uuid));
END;
$$;
COMMENT ON FUNCTION public.is_agent_folder_admin(text) IS
    'agent-brand object path check: <orgId>/<agentId|org>/<file> where the caller holds agents.edit on orgId.';

DROP POLICY IF EXISTS "agent_org_defaults admin select" ON public.agent_org_defaults;
DROP POLICY IF EXISTS "agent_org_defaults admin insert" ON public.agent_org_defaults;
DROP POLICY IF EXISTS "agent_org_defaults admin update" ON public.agent_org_defaults;
CREATE POLICY "agent_org_defaults view" ON public.agent_org_defaults
    FOR SELECT TO authenticated USING (public.has_org_permission(org_id, 'agents.view'));
CREATE POLICY "agent_org_defaults edit insert" ON public.agent_org_defaults
    FOR INSERT TO authenticated WITH CHECK (public.has_org_permission(org_id, 'agents.edit'));
CREATE POLICY "agent_org_defaults edit update" ON public.agent_org_defaults
    FOR UPDATE TO authenticated
    USING (public.has_org_permission(org_id, 'agents.edit')) WITH CHECK (public.has_org_permission(org_id, 'agents.edit'));

-- Domains: readable with agents.view; add/delete/verify need agents.domains.
DROP POLICY IF EXISTS "agent_domains admin select" ON public.agent_domains;
DROP POLICY IF EXISTS "agent_domains admin insert" ON public.agent_domains;
DROP POLICY IF EXISTS "agent_domains admin delete" ON public.agent_domains;
CREATE POLICY "agent_domains view" ON public.agent_domains
    FOR SELECT TO authenticated USING (public.has_agent_permission(agent_id, 'agents.view'));
CREATE POLICY "agent_domains domains insert" ON public.agent_domains
    FOR INSERT TO authenticated
    WITH CHECK (public.has_agent_permission(agent_id, 'agents.domains') AND created_by = (SELECT auth.uid()));
CREATE POLICY "agent_domains domains delete" ON public.agent_domains
    FOR DELETE TO authenticated USING (public.has_agent_permission(agent_id, 'agents.domains'));

CREATE OR REPLACE FUNCTION public.agent_domains_guard() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    problem text;
BEGIN
    IF TG_OP = 'INSERT' THEN
        -- Before any check that could reveal another org's agent (RLS WITH CHECK runs after this).
        IF NOT public.has_agent_permission(NEW.agent_id, 'agents.domains') THEN
            RAISE EXCEPTION 'Permission denied' USING ERRCODE = '42501';
        END IF;
        problem := public.agent_hostname_error(NEW.hostname::text);
        IF problem IS NOT NULL THEN
            RAISE EXCEPTION '%', problem USING ERRCODE = '23514';
        END IF;
        PERFORM 1 FROM public.agents a WHERE a.id = NEW.agent_id FOR UPDATE;
        IF (SELECT count(*) FROM public.agent_domains d WHERE d.agent_id = NEW.agent_id) >= 5 THEN
            RAISE EXCEPTION 'domain_limit_reached' USING ERRCODE = 'P0001';
        END IF;
        NEW.status := 'pending';
        NEW.last_checked_at := NULL;
        NEW.verified_at := NULL;
        NEW.verify_token := pg_catalog.replace(gen_random_uuid()::text, '-', '');
        NEW.created_at := now();
    ELSIF NEW.hostname IS DISTINCT FROM OLD.hostname OR NEW.agent_id IS DISTINCT FROM OLD.agent_id
          OR NEW.verify_token IS DISTINCT FROM OLD.verify_token THEN
        RAISE EXCEPTION 'domain_immutable' USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
END;
$$;

-- The edge function checks agents.domains with the caller's JWT first; the actor it passes must
-- also hold it here, so a stale or forged actor cannot move a status.
CREATE OR REPLACE FUNCTION public.agent_domain_record_check(p_domain_id uuid, p_txt_ok boolean,
                                                           p_cname_ok boolean, p_actor uuid)
RETURNS text LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    new_status text := CASE WHEN p_txt_ok AND p_cname_ok THEN 'active'
                            WHEN p_txt_ok THEN 'verified' ELSE 'failed' END;
    v_agent uuid;
BEGIN
    SELECT d.agent_id INTO v_agent FROM public.agent_domains d WHERE d.id = p_domain_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'domain_not_found' USING ERRCODE = 'P0001';
    END IF;
    IF NOT public.user_has_agent_permission(p_actor, v_agent, 'agents.domains') THEN
        RAISE EXCEPTION 'Permission denied' USING ERRCODE = '42501';
    END IF;
    PERFORM pg_catalog.set_config('agents.audit_actor', COALESCE(p_actor::text, ''), true);
    UPDATE public.agent_domains
       SET status = new_status, last_checked_at = now(),
           verified_at = CASE WHEN new_status = 'failed' THEN verified_at
                              ELSE COALESCE(verified_at, now()) END
     WHERE id = p_domain_id;
    PERFORM pg_catalog.set_config('agents.audit_actor', '', true);
    RETURN new_status;
END;
$$;

-- Org console RPCs: agents.view.
CREATE OR REPLACE FUNCTION public.org_console_require_view(p_org_id uuid) RETURNS void
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
BEGIN
    IF auth.uid() IS NULL OR p_org_id IS NULL OR NOT public.has_org_permission(p_org_id, 'agents.view') THEN
        RAISE EXCEPTION 'Permission denied' USING ERRCODE = '42501';
    END IF;
END;
$$;
REVOKE ALL ON FUNCTION public.org_console_require_view(uuid) FROM PUBLIC, anon, authenticated;

CREATE OR REPLACE FUNCTION public.org_overview(p_org_id uuid) RETURNS jsonb
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    today date := (now() AT TIME ZONE 'UTC')::date;
BEGIN
    PERFORM public.org_console_require_view(p_org_id);
    RETURN jsonb_build_object(
        'agents_by_status', (
            SELECT jsonb_build_object(
                'draft', count(*) FILTER (WHERE a.status = 'draft'),
                'pending_review', count(*) FILTER (WHERE a.status = 'pending_review'),
                'live', count(*) FILTER (WHERE a.status = 'live'),
                'suspended', count(*) FILTER (WHERE a.status = 'suspended'))
            FROM public.agents a WHERE a.org_id = p_org_id),
        'installs_total', (
            SELECT count(*) FROM public.agent_installs i JOIN public.agents a ON a.id = i.agent_id
            WHERE a.org_id = p_org_id AND i.state <> 'removed'),
        'people_total', (
            SELECT count(DISTINCT i.user_id) FROM public.agent_installs i JOIN public.agents a ON a.id = i.agent_id
            WHERE a.org_id = p_org_id AND i.state <> 'removed'),
        'active_people_7d', (
            SELECT count(DISTINCT d.user_id) FROM public.agent_activity_days d JOIN public.agents a ON a.id = d.agent_id
            WHERE a.org_id = p_org_id AND d.day > today - 7),
        'active_people_30d', (
            SELECT count(DISTINCT d.user_id) FROM public.agent_activity_days d JOIN public.agents a ON a.id = d.agent_id
            WHERE a.org_id = p_org_id AND d.day > today - 30),
        'pods', (
            SELECT jsonb_build_object(
                'running', count(*) FILTER (WHERE i.state = 'running'),
                'failed', count(*) FILTER (WHERE i.state = 'failed'),
                'pending', count(*) FILTER (WHERE i.state = 'pending'))
            FROM public.agent_installs i JOIN public.agents a ON a.id = i.agent_id WHERE a.org_id = p_org_id));
END;
$$;

CREATE OR REPLACE FUNCTION public.org_people(p_org_id uuid, p_limit int DEFAULT 500, p_offset int DEFAULT 0)
RETURNS TABLE (user_id uuid, email text, first_seen_at timestamptz, last_seen_at timestamptz, agents jsonb)
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
#variable_conflict use_column
BEGIN
    PERFORM public.org_console_require_view(p_org_id);
    RETURN QUERY
        SELECT i.user_id, u.email::text, min(i.created_at), max(i.last_seen_at),
               jsonb_agg(jsonb_build_object(
                   'agent_id', a.id, 'slug', a.slug, 'name', a.name, 'state', i.state,
                   'joined_at', i.created_at, 'last_seen_at', i.last_seen_at,
                   'running_version', i.running_version) ORDER BY i.created_at)
        FROM public.agent_installs i
        JOIN public.agents a ON a.id = i.agent_id
        JOIN auth.users u ON u.id = i.user_id
        WHERE a.org_id = p_org_id
        GROUP BY i.user_id, u.email
        ORDER BY max(i.last_seen_at) DESC NULLS LAST, min(i.created_at) DESC, i.user_id
        LIMIT LEAST(GREATEST(COALESCE(p_limit, 500), 1), 1000) OFFSET GREATEST(COALESCE(p_offset, 0), 0);
END;
$$;

CREATE OR REPLACE FUNCTION public.org_usage(p_org_id uuid, p_days int DEFAULT 30)
RETURNS TABLE (day date, agent_id uuid, slug text, joins int, active_people int)
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
#variable_conflict use_column
DECLARE
    today date := (now() AT TIME ZONE 'UTC')::date;
    n int := LEAST(GREATEST(COALESCE(p_days, 30), 1), 90);
BEGIN
    PERFORM public.org_console_require_view(p_org_id);
    RETURN QUERY
        SELECT g.day, a.id, a.slug,
               (SELECT count(*)::int FROM public.agent_installs i
                WHERE i.agent_id = a.id AND (i.created_at AT TIME ZONE 'UTC')::date = g.day),
               (SELECT count(*)::int FROM public.agent_activity_days d
                WHERE d.agent_id = a.id AND d.day = g.day)
        FROM public.agents a
        CROSS JOIN (SELECT (today - s)::date AS day FROM pg_catalog.generate_series(0, n - 1) s) g
        WHERE a.org_id = p_org_id
        ORDER BY g.day, a.slug;
END;
$$;

CREATE OR REPLACE FUNCTION public.org_audit_log(p_org_id uuid, p_limit int DEFAULT 50, p_before timestamptz DEFAULT NULL)
RETURNS TABLE (id bigint, agent_id uuid, agent_slug text, actor uuid, actor_email text, action text,
               detail jsonb, at timestamptz)
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
#variable_conflict use_column
BEGIN
    PERFORM public.org_console_require_view(p_org_id);
    RETURN QUERY
        SELECT l.id, l.agent_id, a.slug, l.actor, u.email::text, l.action, l.detail, l.at
        FROM public.agent_audit_log l
        LEFT JOIN public.agents a ON a.id = l.agent_id
        LEFT JOIN auth.users u ON u.id = l.actor
        WHERE l.org_id = p_org_id AND (p_before IS NULL OR l.at < p_before)
        ORDER BY l.at DESC, l.id DESC
        LIMIT LEAST(GREATEST(COALESCE(p_limit, 50), 1), 200);
END;
$$;

-- Model picker: anyone who may view agents in some org (catalog ids and labels only).
CREATE OR REPLACE FUNCTION public.list_agent_models()
RETURNS TABLE (provider_id text, model_id text, display_name text, description text)
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
#variable_conflict use_column
BEGIN
    IF auth.uid() IS NULL THEN
        RETURN;
    END IF;
    IF NOT (public.is_user_admin(auth.uid())
            OR EXISTS (SELECT 1 FROM public.organisation_members m
                       WHERE m.user_id = auth.uid() AND m.status = 'active'
                         AND public.has_org_permission(m.org_id, 'agents.view'))) THEN
        RETURN;
    END IF;
    RETURN QUERY
        SELECT 'managed:boss-ai'::text, m.id, m.display_name,
               pg_catalog.array_to_string(m.capabilities, ', ') || ' - ' ||
                   (m.context_length / 1000)::text || 'k context'
        FROM public.boss_ai_models m
        JOIN public.boss_ai_connections c ON c.id = m.connection_id
        WHERE m.enabled AND m.published AND c.enabled AND m.kind = 'chat'
        ORDER BY m.is_default DESC, m.display_name, m.id;
END;
$$;

-- Removing a person's install (agents.access). The provisioner tears down installs in state removed.
CREATE OR REPLACE FUNCTION public.remove_agent_install(p_agent_id uuid, p_user_id uuid) RETURNS boolean
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    v_org uuid;
BEGIN
    IF auth.uid() IS NULL OR NOT public.has_agent_permission(p_agent_id, 'agents.access') THEN
        RAISE EXCEPTION 'Permission denied' USING ERRCODE = '42501';
    END IF;
    SELECT a.org_id INTO v_org FROM public.agents a WHERE a.id = p_agent_id;
    UPDATE public.agent_installs SET state = 'removed', detail = 'removed by the organisation'
        WHERE agent_id = p_agent_id AND user_id = p_user_id AND state <> 'removed';
    IF NOT FOUND THEN
        RETURN false;
    END IF;
    INSERT INTO public.agent_audit_log (org_id, agent_id, actor, action, detail)
        VALUES (v_org, p_agent_id, auth.uid(), 'install.remove', jsonb_build_object(
            'user_id', p_user_id, 'email', (SELECT u.email FROM auth.users u WHERE u.id = p_user_id)));
    RETURN true;
END;
$$;
REVOKE ALL ON FUNCTION public.remove_agent_install(uuid, uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.remove_agent_install(uuid, uuid) TO authenticated, service_role;

-- ----------------------------------------------------------------------------
-- 5. Audit: role and permission changes that affect agents. Org roles only; agents.* grants only;
--    assignments of the admin-kind role or of a role whose same-org closure carries agents.*.
--    agents.audit_suppress (transaction-local) silences the built-in role seed.
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.agents_audit_suppressed() RETURNS boolean
    LANGUAGE sql STABLE SET search_path = '' AS $$
    SELECT COALESCE(pg_catalog.current_setting('agents.audit_suppress', true), '') = 'on';
$$;
REVOKE ALL ON FUNCTION public.agents_audit_suppressed() FROM PUBLIC, anon, authenticated;

CREATE OR REPLACE FUNCTION public.org_role_affects_agents(p_role_id uuid) RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
    SELECT EXISTS (
        SELECT 1 FROM public.organisation_roles orl
        WHERE orl.role_id = p_role_id
          AND (orl.kind = 'admin' OR EXISTS (
              SELECT 1
              FROM public.get_role_descendants(p_role_id) AS d(role_id)
              JOIN public.organisation_roles dorl ON dorl.role_id = d.role_id AND dorl.org_id = orl.org_id
              JOIN public.role_permissions rp ON rp.role_id = d.role_id
              JOIN public.permissions p ON p.id = rp.permission_id
              WHERE p.name = ANY (public.agents_permission_catalog()))));
$$;
REVOKE ALL ON FUNCTION public.org_role_affects_agents(uuid) FROM PUBLIC, anon, authenticated;

CREATE OR REPLACE FUNCTION public.org_roles_audit() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    v_org uuid;
    v_role uuid;
    v_name text;
    v_perm text;
BEGIN
    IF public.agents_audit_suppressed() THEN
        RETURN NULL;
    END IF;
    IF TG_TABLE_NAME = 'user_roles' THEN
        v_role := CASE WHEN TG_OP = 'DELETE' THEN OLD.role_id ELSE NEW.role_id END;
        SELECT orl.org_id INTO v_org FROM public.organisation_roles orl WHERE orl.role_id = v_role;
        IF v_org IS NULL OR NOT public.org_role_affects_agents(v_role) THEN
            RETURN NULL;
        END IF;
        SELECT r.name INTO v_name FROM public.roles r WHERE r.id = v_role;
        INSERT INTO public.agent_audit_log (org_id, agent_id, actor, action, detail)
            VALUES (v_org, NULL, public.agent_audit_actor(),
                    CASE WHEN TG_OP = 'DELETE' THEN 'member.role_remove' ELSE 'member.role_assign' END,
                    jsonb_build_object(
                        'user_id', CASE WHEN TG_OP = 'DELETE' THEN OLD.user_id ELSE NEW.user_id END,
                        'email', (SELECT u.email FROM auth.users u
                                  WHERE u.id = CASE WHEN TG_OP = 'DELETE' THEN OLD.user_id ELSE NEW.user_id END),
                        'role_id', v_role, 'role', v_name));
    ELSIF TG_TABLE_NAME = 'role_permissions' THEN
        v_role := CASE WHEN TG_OP = 'DELETE' THEN OLD.role_id ELSE NEW.role_id END;
        SELECT p.name INTO v_perm FROM public.permissions p
            WHERE p.id = CASE WHEN TG_OP = 'DELETE' THEN OLD.permission_id ELSE NEW.permission_id END;
        IF v_perm IS NULL OR NOT (v_perm = ANY (public.agents_permission_catalog())) THEN
            RETURN NULL;
        END IF;
        SELECT orl.org_id INTO v_org FROM public.organisation_roles orl WHERE orl.role_id = v_role;
        IF v_org IS NULL THEN
            RETURN NULL;
        END IF;
        SELECT r.name INTO v_name FROM public.roles r WHERE r.id = v_role;
        INSERT INTO public.agent_audit_log (org_id, agent_id, actor, action, detail)
            VALUES (v_org, NULL, public.agent_audit_actor(),
                    CASE WHEN TG_OP = 'DELETE' THEN 'role.permission_revoke' ELSE 'role.permission_grant' END,
                    jsonb_build_object('role_id', v_role, 'role', v_name, 'permission', v_perm));
    ELSIF TG_TABLE_NAME = 'organisation_roles' THEN
        IF NEW.kind <> 'custom' THEN
            RETURN NULL;
        END IF;
        SELECT r.name INTO v_name FROM public.roles r WHERE r.id = NEW.role_id;
        INSERT INTO public.agent_audit_log (org_id, agent_id, actor, action, detail)
            VALUES (NEW.org_id, NULL, public.agent_audit_actor(), 'role.create',
                    jsonb_build_object('role_id', NEW.role_id, 'role', v_name));
    END IF;
    RETURN NULL;
END;
$$;
REVOKE ALL ON FUNCTION public.org_roles_audit() FROM PUBLIC, anon, authenticated;
DROP TRIGGER IF EXISTS user_roles_agents_audit ON public.user_roles;
CREATE TRIGGER user_roles_agents_audit AFTER INSERT OR DELETE ON public.user_roles
    FOR EACH ROW EXECUTE FUNCTION public.org_roles_audit();
DROP TRIGGER IF EXISTS role_permissions_agents_audit ON public.role_permissions;
CREATE TRIGGER role_permissions_agents_audit AFTER INSERT OR DELETE ON public.role_permissions
    FOR EACH ROW EXECUTE FUNCTION public.org_roles_audit();
DROP TRIGGER IF EXISTS organisation_roles_agents_audit ON public.organisation_roles;
CREATE TRIGGER organisation_roles_agents_audit AFTER INSERT ON public.organisation_roles
    FOR EACH ROW EXECUTE FUNCTION public.org_roles_audit();

-- Deleting a role cascades its mapping away, so the delete is logged while the mapping still exists.
CREATE OR REPLACE FUNCTION public.org_role_delete_audit() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    v_org uuid;
BEGIN
    SELECT orl.org_id INTO v_org FROM public.organisation_roles orl WHERE orl.role_id = OLD.id AND orl.kind = 'custom';
    IF v_org IS NOT NULL AND NOT public.agents_audit_suppressed() THEN
        INSERT INTO public.agent_audit_log (org_id, agent_id, actor, action, detail)
            VALUES (v_org, NULL, public.agent_audit_actor(), 'role.delete',
                    jsonb_build_object('role_id', OLD.id, 'role', OLD.name));
    END IF;
    RETURN OLD;
END;
$$;
REVOKE ALL ON FUNCTION public.org_role_delete_audit() FROM PUBLIC, anon, authenticated;
DROP TRIGGER IF EXISTS roles_agents_delete_audit ON public.roles;
CREATE TRIGGER roles_agents_delete_audit BEFORE DELETE ON public.roles
    FOR EACH ROW EXECUTE FUNCTION public.org_role_delete_audit();

-- ----------------------------------------------------------------------------
-- 6. Email-bound invites. An invite with an email can be redeemed only by a signed-in user whose
--    CONFIRMED email matches; the failure is the same message as an invalid token.
-- ----------------------------------------------------------------------------
ALTER TABLE public.organisation_invites ADD COLUMN IF NOT EXISTS email extensions.citext;
COMMENT ON COLUMN public.organisation_invites.email IS
    'Set by org_team_invite: only the account with this confirmed email may redeem. NULL = a shareable link.';

CREATE OR REPLACE FUNCTION "public"."redeem_organisation_invite"("p_token" "text")
RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
DECLARE
    v_user_id UUID;
    v_hash TEXT;
    v_inv public.organisation_invites;
    v_slug TEXT;
    v_name TEXT;
BEGIN
    v_user_id := auth.uid();
    IF v_user_id IS NULL THEN
        RETURN jsonb_build_object('success', false, 'error', 'Not authenticated');
    END IF;

    IF p_token IS NULL OR btrim(p_token) = '' THEN
        RETURN jsonb_build_object('success', false, 'error', 'Invite link is invalid or expired');
    END IF;

    v_hash := pg_catalog.encode(extensions.digest(btrim(p_token), 'sha256'), 'hex');

    SELECT * INTO v_inv
    FROM public.organisation_invites i
    WHERE i.token_hash = v_hash
    FOR UPDATE;

    IF NOT FOUND THEN
        RETURN jsonb_build_object('success', false, 'error', 'Invite link is invalid or expired');
    END IF;

    -- Email-bound (org_team_invite): anyone else gets the invalid-link answer, before any state.
    IF v_inv.email IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM auth.users u
        WHERE u.id = v_user_id AND u.email_confirmed_at IS NOT NULL
          AND lower(u.email) = lower(v_inv.email::text)
    ) THEN
        RETURN jsonb_build_object('success', false, 'error', 'Invite link is invalid or expired');
    END IF;

    SELECT o.slug, o.name INTO v_slug, v_name
    FROM public.organisations o WHERE o.id = v_inv.org_id;

    IF EXISTS (
        SELECT 1 FROM public.organisation_invite_redemptions red
        WHERE red.invite_id = v_inv.id AND red.user_id = v_user_id
    ) AND EXISTS (
        SELECT 1 FROM public.organisation_members m
        WHERE m.org_id = v_inv.org_id AND m.user_id = v_user_id AND m.status = 'active'
    ) THEN
        RETURN jsonb_build_object('success', true, 'already_member', true,
            'org_id', v_inv.org_id::text, 'slug', v_slug, 'name', v_name);
    END IF;

    IF v_inv.revoked_at IS NOT NULL
       OR v_inv.expires_at <= now()
       OR (v_inv.max_uses IS NOT NULL AND v_inv.uses >= v_inv.max_uses) THEN
        RETURN jsonb_build_object('success', false, 'error', 'Invite link is invalid or expired');
    END IF;

    INSERT INTO public.organisation_members (
        org_id, user_id, status, joined_at, join_source, invited_by, invited_at
    ) VALUES (
        v_inv.org_id, v_user_id, 'active', now(), 'invite', v_inv.created_by, v_inv.created_at
    )
    ON CONFLICT (org_id, user_id) DO UPDATE
        SET status = 'active',
            joined_at = COALESCE(organisation_members.joined_at, now()),
            updated_at = now();

    IF v_inv.role_id IS NOT NULL THEN
        INSERT INTO public.user_roles (user_id, role_id, assigned_by, assigned_at)
        VALUES (v_user_id, v_inv.role_id, v_inv.created_by, now())
        ON CONFLICT (user_id, role_id) DO NOTHING;
    ELSE
        PERFORM public.assign_org_member_role_internal(v_inv.org_id, v_user_id);
    END IF;

    INSERT INTO public.organisation_invite_redemptions (invite_id, user_id)
    VALUES (v_inv.id, v_user_id)
    ON CONFLICT (invite_id, user_id) DO NOTHING;

    IF FOUND THEN
        UPDATE public.organisation_invites SET uses = uses + 1 WHERE id = v_inv.id;
    END IF;

    RETURN jsonb_build_object('success', true, 'org_id', v_inv.org_id::text,
        'slug', v_slug, 'name', v_name, 'status', 'active');
END;
$$;
ALTER FUNCTION "public"."redeem_organisation_invite"("text") OWNER TO "postgres";
REVOKE EXECUTE ON FUNCTION "public"."redeem_organisation_invite"("text") FROM PUBLIC, "anon";
GRANT EXECUTE ON FUNCTION "public"."redeem_organisation_invite"("text") TO "authenticated", "service_role";

-- ----------------------------------------------------------------------------
-- 7. Team RPCs (console Team page). Thin wrappers over the org RPCs; they raise instead of returning
--    {success:false}: 42501 'Permission denied' for an unauthorized caller, P0001 '<message>' else.
--    Membership and role management stay org-admin only, as in the org RPCs.
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.org_team_unwrap(p_result jsonb) RETURNS jsonb
    LANGUAGE plpgsql IMMUTABLE SET search_path = '' AS $$
BEGIN
    IF COALESCE((p_result->>'success')::boolean, false) THEN
        RETURN p_result;
    END IF;
    IF (p_result->>'error') IN ('Not authenticated', 'Permission denied', 'Organisation not found') THEN
        RAISE EXCEPTION 'Permission denied' USING ERRCODE = '42501';
    END IF;
    RAISE EXCEPTION '%', COALESCE(p_result->>'error', 'failed') USING ERRCODE = 'P0001';
END;
$$;
REVOKE ALL ON FUNCTION public.org_team_unwrap(jsonb) FROM PUBLIC, anon, authenticated;

CREATE OR REPLACE FUNCTION public.org_team_require_admin(p_org_id uuid) RETURNS uuid
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
BEGIN
    IF auth.uid() IS NULL OR p_org_id IS NULL OR NOT public.is_org_admin(p_org_id) THEN
        RAISE EXCEPTION 'Permission denied' USING ERRCODE = '42501';
    END IF;
    RETURN auth.uid();
END;
$$;
REVOKE ALL ON FUNCTION public.org_team_require_admin(uuid) FROM PUBLIC, anon, authenticated;

-- Who may hand out a role: the existing delegation rule (get_grantable_role_ids: strict descendants
-- of the caller's roles, every role for a global admin). The admin-kind role is the caller's own
-- tier, so only the owner (or a global admin) may grant or take it.
CREATE OR REPLACE FUNCTION public.org_team_can_grant(p_actor uuid, p_org_id uuid, p_role_id uuid) RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
    SELECT EXISTS (
        SELECT 1 FROM public.organisation_roles orl
        WHERE orl.org_id = p_org_id AND orl.role_id = p_role_id
          AND public.user_is_org_admin(p_actor, p_org_id)
          AND CASE WHEN orl.kind = 'admin'
                   THEN public.is_user_admin(p_actor)
                        OR EXISTS (SELECT 1 FROM public.organisations o WHERE o.id = p_org_id AND o.owner_id = p_actor)
                   ELSE p_role_id IN (SELECT public.get_grantable_role_ids(p_actor)) END);
$$;
REVOKE ALL ON FUNCTION public.org_team_can_grant(uuid, uuid, uuid) FROM PUBLIC, anon, authenticated;

CREATE OR REPLACE FUNCTION public.org_team_members(p_org_id uuid, p_limit int DEFAULT 500, p_offset int DEFAULT 0)
RETURNS TABLE (user_id uuid, email text, display_name text, status text, joined_at timestamptz,
               requested_at timestamptz, is_owner boolean, is_admin boolean, roles jsonb)
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
#variable_conflict use_column
DECLARE
    uid uuid := auth.uid();
    v_admin boolean;
    v_system boolean;
BEGIN
    IF uid IS NULL OR p_org_id IS NULL OR NOT public.is_org_member(p_org_id) THEN
        RAISE EXCEPTION 'Permission denied' USING ERRCODE = '42501';
    END IF;
    v_admin := public.is_org_admin(p_org_id);
    SELECT o.is_system INTO v_system FROM public.organisations o WHERE o.id = p_org_id;
    RETURN QUERY
        SELECT m.user_id, u.email::text,
               COALESCE(NULLIF(btrim(u.raw_user_meta_data->>'full_name'), ''),
                        NULLIF(btrim(u.raw_user_meta_data->>'name'), ''),
                        pg_catalog.split_part(u.email::text, '@', 1)),
               CASE m.status WHEN 'pending' THEN 'pending_approval' ELSE m.status END,
               m.joined_at, m.requested_at,
               m.user_id = o.owner_id,
               public.user_is_org_admin(m.user_id, p_org_id),
               COALESCE((SELECT jsonb_agg(jsonb_build_object('role_id', r.id, 'name', r.name, 'kind', orl.kind,
                                                             'preset', orl.preset) ORDER BY r.name)
                         FROM public.user_roles ur
                         JOIN public.organisation_roles orl ON orl.role_id = ur.role_id AND orl.org_id = p_org_id
                         JOIN public.roles r ON r.id = ur.role_id
                         WHERE ur.user_id = m.user_id), '[]'::jsonb)
        FROM public.organisation_members m
        JOIN public.organisations o ON o.id = m.org_id
        LEFT JOIN auth.users u ON u.id = m.user_id
        WHERE m.org_id = p_org_id
          AND (v_admin OR m.status = 'active')
          -- A system org's roster is every account: non-admins see only themselves (as list_organisation_members).
          AND (v_admin OR NOT COALESCE(v_system, false) OR m.user_id = uid)
        ORDER BY (m.user_id = o.owner_id) DESC, m.status, u.email
        LIMIT LEAST(GREATEST(COALESCE(p_limit, 500), 1), 1000) OFFSET GREATEST(COALESCE(p_offset, 0), 0);
END;
$$;

CREATE OR REPLACE FUNCTION public.org_team_invites(p_org_id uuid, p_include_inactive boolean DEFAULT false)
RETURNS TABLE (invite_id uuid, email text, role_id uuid, role_name text, label text, uses int, max_uses int,
               expires_at timestamptz, revoked_at timestamptz, created_at timestamptz, created_by_email text,
               is_live boolean)
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
#variable_conflict use_column
BEGIN
    PERFORM public.org_team_require_admin(p_org_id);
    RETURN QUERY
        SELECT i.id, i.email::text, i.role_id, r.name, i.label, i.uses, i.max_uses, i.expires_at, i.revoked_at,
               i.created_at, cu.email::text, x.live
        FROM public.organisation_invites i
        LEFT JOIN public.roles r ON r.id = i.role_id
        LEFT JOIN auth.users cu ON cu.id = i.created_by
        CROSS JOIN LATERAL (SELECT (i.revoked_at IS NULL AND i.expires_at > now()
                                    AND (i.max_uses IS NULL OR i.uses < i.max_uses)) AS live) x
        WHERE i.org_id = p_org_id AND (p_include_inactive OR x.live)
        ORDER BY i.created_at DESC;
END;
$$;

-- Returns {invite_id, email, token, token_prefix, expires_at}. The token exists only here; the
-- console turns it into <functions>/organisation/join/<token> and sends it. A previous live invite
-- for the same email is revoked, so one person has one live link.
CREATE OR REPLACE FUNCTION public.org_team_invite(p_org_id uuid, p_email text, p_role_id uuid DEFAULT NULL,
                                                 p_expires_in_hours int DEFAULT 168)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    v_actor uuid := public.org_team_require_admin(p_org_id);
    v_email text := pg_catalog.lower(pg_catalog.btrim(COALESCE(p_email, '')));
    v_res jsonb;
BEGIN
    IF pg_catalog.char_length(v_email) > 254 OR v_email !~ '^[^@\s]+@[^@\s]+\.[^@\s]+$' THEN
        RAISE EXCEPTION 'invalid_email' USING ERRCODE = 'P0001';
    END IF;
    IF EXISTS (SELECT 1 FROM public.organisation_members m JOIN auth.users u ON u.id = m.user_id
               WHERE m.org_id = p_org_id AND m.status = 'active' AND lower(u.email) = v_email) THEN
        RAISE EXCEPTION 'already_member' USING ERRCODE = 'P0001';
    END IF;
    IF p_role_id IS NOT NULL AND NOT public.org_team_can_grant(v_actor, p_org_id, p_role_id) THEN
        RAISE EXCEPTION 'role_not_grantable' USING ERRCODE = 'P0001';
    END IF;
    UPDATE public.organisation_invites SET revoked_at = now(), revoked_by = v_actor
        WHERE org_id = p_org_id AND email = v_email::extensions.citext AND revoked_at IS NULL;
    v_res := public.org_team_unwrap(public.create_organisation_invite(
        p_org_id, p_role_id, v_email, 1, p_expires_in_hours, NULL));
    UPDATE public.organisation_invites SET email = v_email WHERE id = (v_res->>'invite_id')::uuid;
    INSERT INTO public.agent_audit_log (org_id, agent_id, actor, action, detail)
        VALUES (p_org_id, NULL, v_actor, 'team.invite_add', jsonb_build_object(
            'invite_id', v_res->>'invite_id', 'email', v_email, 'role_id', p_role_id,
            'role', (SELECT r.name FROM public.roles r WHERE r.id = p_role_id)));
    RETURN jsonb_build_object('invite_id', v_res->>'invite_id', 'email', v_email, 'token', v_res->>'token',
                              'token_prefix', v_res->>'token_prefix', 'expires_at', v_res->'expires_at');
END;
$$;

CREATE OR REPLACE FUNCTION public.org_team_revoke_invite(p_org_id uuid, p_invite_id uuid) RETURNS void
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    v_actor uuid := public.org_team_require_admin(p_org_id);
    v_email text;
BEGIN
    SELECT i.email::text INTO v_email FROM public.organisation_invites i WHERE i.id = p_invite_id AND i.org_id = p_org_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Invite not found' USING ERRCODE = 'P0001';
    END IF;
    PERFORM public.org_team_unwrap(public.revoke_organisation_invite(p_invite_id, NULL));
    INSERT INTO public.agent_audit_log (org_id, agent_id, actor, action, detail)
        VALUES (p_org_id, NULL, v_actor, 'team.invite_revoke', jsonb_build_object('invite_id', p_invite_id, 'email', v_email));
END;
$$;

CREATE OR REPLACE FUNCTION public.org_team_approve(p_org_id uuid, p_user_id uuid) RETURNS void
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    v_actor uuid := public.org_team_require_admin(p_org_id);
BEGIN
    PERFORM public.org_team_unwrap(public.approve_organisation_member(p_org_id, p_user_id, NULL));
    INSERT INTO public.agent_audit_log (org_id, agent_id, actor, action, detail)
        VALUES (p_org_id, NULL, v_actor, 'team.member_approve', jsonb_build_object('user_id', p_user_id,
                'email', (SELECT u.email FROM auth.users u WHERE u.id = p_user_id)));
END;
$$;

CREATE OR REPLACE FUNCTION public.org_team_deny(p_org_id uuid, p_user_id uuid) RETURNS void
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    v_actor uuid := public.org_team_require_admin(p_org_id);
BEGIN
    PERFORM public.org_team_unwrap(public.reject_organisation_member(p_org_id, p_user_id, NULL));
    INSERT INTO public.agent_audit_log (org_id, agent_id, actor, action, detail)
        VALUES (p_org_id, NULL, v_actor, 'team.member_deny', jsonb_build_object('user_id', p_user_id,
                'email', (SELECT u.email FROM auth.users u WHERE u.id = p_user_id)));
END;
$$;

-- Owner and last-admin protections are remove_organisation_member's; its role cleanup is audited by
-- the user_roles trigger.
CREATE OR REPLACE FUNCTION public.org_team_remove(p_org_id uuid, p_user_id uuid) RETURNS void
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    v_actor uuid := public.org_team_require_admin(p_org_id);
    v_email text := (SELECT u.email FROM auth.users u WHERE u.id = p_user_id);
BEGIN
    PERFORM public.org_team_unwrap(public.remove_organisation_member(p_org_id, p_user_id, NULL));
    INSERT INTO public.agent_audit_log (org_id, agent_id, actor, action, detail)
        VALUES (p_org_id, NULL, v_actor, 'team.member_remove', jsonb_build_object('user_id', p_user_id, 'email', v_email));
END;
$$;

-- No self-assignment; the role must be grantable by the caller (see org_team_can_grant).
CREATE OR REPLACE FUNCTION public.org_team_assign_role(p_org_id uuid, p_user_id uuid, p_role_id uuid) RETURNS void
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    v_actor uuid := public.org_team_require_admin(p_org_id);
BEGIN
    IF p_user_id = v_actor AND NOT public.is_user_admin(v_actor) THEN
        RAISE EXCEPTION 'cannot_change_own_roles' USING ERRCODE = '42501';
    END IF;
    IF NOT public.org_team_can_grant(v_actor, p_org_id, p_role_id) THEN
        RAISE EXCEPTION 'role_not_grantable' USING ERRCODE = '42501';
    END IF;
    PERFORM public.org_team_unwrap(public.assign_organisation_role(p_org_id, p_user_id, p_role_id, NULL));
END;
$$;

-- Owner and last-admin protections are remove_organisation_role's.
CREATE OR REPLACE FUNCTION public.org_team_remove_role(p_org_id uuid, p_user_id uuid, p_role_id uuid) RETURNS void
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    v_actor uuid := public.org_team_require_admin(p_org_id);
BEGIN
    IF NOT public.org_team_can_grant(v_actor, p_org_id, p_role_id) THEN
        RAISE EXCEPTION 'role_not_grantable' USING ERRCODE = '42501';
    END IF;
    PERFORM public.org_team_unwrap(public.remove_organisation_role(p_org_id, p_user_id, p_role_id, NULL));
END;
$$;

-- permissions: everything the role holds directly (agents.* and anything else).
-- agent_permissions: what its holders get in the agents console (admin-kind: all; else the same-org
-- closure plus the implied agents.view).
CREATE OR REPLACE FUNCTION public.org_team_roles(p_org_id uuid)
RETURNS TABLE (role_id uuid, name text, kind text, preset text, description text, permissions text[],
               agent_permissions text[], member_count int, grantable boolean)
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
#variable_conflict use_column
DECLARE
    uid uuid := auth.uid();
BEGIN
    IF uid IS NULL OR p_org_id IS NULL OR NOT public.is_org_member(p_org_id) THEN
        RAISE EXCEPTION 'Permission denied' USING ERRCODE = '42501';
    END IF;
    RETURN QUERY
        SELECT orl.role_id, r.name, orl.kind, orl.preset, r.description,
               COALESCE((SELECT array_agg(p.name ORDER BY p.name) FROM public.role_permissions rp
                         JOIN public.permissions p ON p.id = rp.permission_id WHERE rp.role_id = orl.role_id),
                        ARRAY[]::text[]),
               CASE WHEN orl.kind = 'admin' THEN public.agents_permission_catalog()
                    ELSE COALESCE(ARRAY(
                        SELECT c.perm FROM unnest(public.agents_permission_catalog()) WITH ORDINALITY AS c(perm, n)
                        WHERE EXISTS (
                            SELECT 1 FROM public.get_role_descendants(orl.role_id) AS d(role_id)
                            JOIN public.organisation_roles dorl ON dorl.role_id = d.role_id AND dorl.org_id = p_org_id
                            JOIN public.role_permissions rp ON rp.role_id = d.role_id
                            JOIN public.permissions p ON p.id = rp.permission_id
                            WHERE p.name = c.perm
                               OR (c.perm = 'agents.view' AND p.name = ANY (public.agents_permission_catalog())))
                        ORDER BY c.n), ARRAY[]::text[]) END,
               (SELECT count(*)::int FROM public.user_roles ur
                JOIN public.organisation_members m ON m.user_id = ur.user_id AND m.org_id = p_org_id AND m.status = 'active'
                WHERE ur.role_id = orl.role_id),
               public.org_team_can_grant(uid, p_org_id, orl.role_id)
        FROM public.organisation_roles orl
        JOIN public.roles r ON r.id = orl.role_id
        WHERE orl.org_id = p_org_id
        ORDER BY CASE orl.kind WHEN 'admin' THEN 0 WHEN 'user' THEN 2 ELSE 1 END,
                 CASE orl.preset WHEN 'agent_manager' THEN 0 WHEN 'agent_editor' THEN 1 WHEN 'agent_analyst' THEN 2 ELSE 3 END,
                 r.name;
END;
$$;

CREATE OR REPLACE FUNCTION public.org_team_normalise_permissions(p_permissions text[]) RETURNS text[]
    LANGUAGE plpgsql IMMUTABLE SET search_path = '' AS $$
DECLARE
    bad text;
BEGIN
    SELECT x INTO bad FROM unnest(COALESCE(p_permissions, ARRAY[]::text[])) x
        WHERE x IS NULL OR NOT (x = ANY (public.agents_permission_catalog())) LIMIT 1;
    IF FOUND THEN
        RAISE EXCEPTION 'invalid_permission:%', COALESCE(bad, 'null') USING ERRCODE = 'P0001';
    END IF;
    IF pg_catalog.cardinality(p_permissions) > 0 THEN
        p_permissions := p_permissions || ARRAY['agents.view'];
    END IF;
    RETURN ARRAY(SELECT DISTINCT x FROM unnest(COALESCE(p_permissions, ARRAY[]::text[])) x ORDER BY x);
END;
$$;
REVOKE ALL ON FUNCTION public.org_team_normalise_permissions(text[]) FROM PUBLIC, anon, authenticated;

-- Sets the role's agents.* permissions to exactly p_permissions (other permissions untouched).
-- The admin-kind role always has all of them and is refused.
CREATE OR REPLACE FUNCTION public.org_team_update_role(p_org_id uuid, p_role_id uuid, p_permissions text[],
                                                      p_description text DEFAULT NULL)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    v_actor uuid := public.org_team_require_admin(p_org_id);
    v_kind text;
    v_perms text[] := public.org_team_normalise_permissions(p_permissions);
BEGIN
    SELECT orl.kind INTO v_kind FROM public.organisation_roles orl WHERE orl.org_id = p_org_id AND orl.role_id = p_role_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'That role does not belong to this organisation' USING ERRCODE = 'P0001';
    END IF;
    IF v_kind = 'admin' THEN
        RAISE EXCEPTION 'admin_role_has_all_permissions' USING ERRCODE = 'P0001';
    END IF;
    IF p_description IS NOT NULL THEN
        IF pg_catalog.char_length(p_description) > 200 THEN
            RAISE EXCEPTION 'description_too_long' USING ERRCODE = 'P0001';
        END IF;
        UPDATE public.roles SET description = NULLIF(pg_catalog.btrim(p_description), '') WHERE id = p_role_id;
    END IF;
    DELETE FROM public.role_permissions rp USING public.permissions p
        WHERE rp.role_id = p_role_id AND p.id = rp.permission_id
          AND p.name = ANY (public.agents_permission_catalog()) AND NOT (p.name = ANY (v_perms));
    INSERT INTO public.role_permissions (role_id, permission_id)
        SELECT p_role_id, p.id FROM public.permissions p WHERE p.name = ANY (v_perms)
        ON CONFLICT (role_id, permission_id) DO NOTHING;
END;
$$;

CREATE OR REPLACE FUNCTION public.org_team_create_role(p_org_id uuid, p_suffix text, p_permissions text[],
                                                      p_description text DEFAULT NULL)
RETURNS uuid LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    v_actor uuid := public.org_team_require_admin(p_org_id);
    v_perms text[] := public.org_team_normalise_permissions(p_permissions);
    v_role uuid;
BEGIN
    IF p_description IS NOT NULL AND pg_catalog.char_length(p_description) > 200 THEN
        RAISE EXCEPTION 'description_too_long' USING ERRCODE = 'P0001';
    END IF;
    v_role := (public.org_team_unwrap(public.create_organisation_role(p_org_id, p_suffix, p_description, NULL))->>'role_id')::uuid;
    INSERT INTO public.role_permissions (role_id, permission_id)
        SELECT v_role, p.id FROM public.permissions p WHERE p.name = ANY (v_perms)
        ON CONFLICT (role_id, permission_id) DO NOTHING;
    RETURN v_role;
END;
$$;

CREATE OR REPLACE FUNCTION public.org_team_delete_role(p_org_id uuid, p_role_id uuid) RETURNS void
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    v_actor uuid := public.org_team_require_admin(p_org_id);
BEGIN
    PERFORM public.org_team_unwrap(public.delete_organisation_role(p_org_id, p_role_id, NULL));
END;
$$;

REVOKE ALL ON FUNCTION public.org_team_members(uuid, int, int), public.org_team_invites(uuid, boolean),
    public.org_team_invite(uuid, text, uuid, int), public.org_team_revoke_invite(uuid, uuid),
    public.org_team_approve(uuid, uuid), public.org_team_deny(uuid, uuid), public.org_team_remove(uuid, uuid),
    public.org_team_assign_role(uuid, uuid, uuid), public.org_team_remove_role(uuid, uuid, uuid),
    public.org_team_roles(uuid), public.org_team_update_role(uuid, uuid, text[], text),
    public.org_team_create_role(uuid, text, text[], text), public.org_team_delete_role(uuid, uuid)
    FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.org_team_members(uuid, int, int), public.org_team_invites(uuid, boolean),
    public.org_team_invite(uuid, text, uuid, int), public.org_team_revoke_invite(uuid, uuid),
    public.org_team_approve(uuid, uuid), public.org_team_deny(uuid, uuid), public.org_team_remove(uuid, uuid),
    public.org_team_assign_role(uuid, uuid, uuid), public.org_team_remove_role(uuid, uuid, uuid),
    public.org_team_roles(uuid), public.org_team_update_role(uuid, uuid, text[], text),
    public.org_team_create_role(uuid, text, text[], text), public.org_team_delete_role(uuid, uuid)
    TO authenticated, service_role;

-- ----------------------------------------------------------------------------
-- 8. Store gate for agent adapter plugins (plugin-store, service role). An id in the agent namespace
--    is publishable only as the adapter of an existing agent, by a holder of agents.publish in that
--    agent's org; the store's other rules (author, publish_policy, plugins.create) do not apply to it.
--    {"agent": false} outside the namespace; else {"agent": true, "org_id": uuid|null, "allowed": bool}.
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.agent_plugin_publish_authz(p_user_id uuid, p_plugin_id text) RETURNS jsonb
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
    SELECT CASE
        WHEN p_plugin_id IS NULL OR pg_catalog.left(p_plugin_id, 35) <> 'ai.rever.boss.plugin.dynamic.agent.'
            THEN jsonb_build_object('agent', false)
        ELSE jsonb_build_object(
            'agent', true,
            'org_id', (SELECT a.org_id FROM public.agents a WHERE a.plugin_id = p_plugin_id),
            'allowed', COALESCE((SELECT public.user_has_org_permission(p_user_id, a.org_id, 'agents.publish')
                                 FROM public.agents a WHERE a.plugin_id = p_plugin_id), false))
    END;
$$;
REVOKE ALL ON FUNCTION public.agent_plugin_publish_authz(uuid, text) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.agent_plugin_publish_authz(uuid, text) TO service_role;
