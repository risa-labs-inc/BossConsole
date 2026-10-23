-- ============================================================================
-- Pod tiers (contract v5 §2): which resources a person's BOSS pod gets. Internal, platform admins only.
--
-- pod_tiers         one row per person with a tier other than the default (no row = standard).
-- pod_tier_history  append-only: every assignment, clear, and every tier the provisioner actually applied
--                   to a pod (the billing seam: "applied" rows are the effective tier over time).
-- platform_audit_log append-only platform-level audit (agent_audit_log needs an org).
--
-- The tier NAMES live in the provisioner's config (OPTIMIST_POD_TIERS); the database only checks the shape.
-- An unknown name is applied as standard by the provisioner.
-- The provisioner bot reads assignments through pod_tier_assignments() and reports what it applied through
-- record_pod_tier_applied(); it has no table access.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- Platform audit log (append-only for everyone, the owner included).
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.platform_audit_log (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    actor       uuid,
    action      text NOT NULL,
    target_user uuid,
    detail      jsonb NOT NULL DEFAULT '{}'::jsonb,
    at          timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX IF NOT EXISTS idx_platform_audit_log_at ON public.platform_audit_log (at DESC, id DESC);
ALTER TABLE public.platform_audit_log ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.platform_audit_log FROM PUBLIC, anon, authenticated, service_role;
GRANT SELECT ON public.platform_audit_log TO authenticated, service_role;
DROP POLICY IF EXISTS "platform_audit_log admin select" ON public.platform_audit_log;
CREATE POLICY "platform_audit_log admin select" ON public.platform_audit_log
    FOR SELECT TO authenticated USING ((SELECT public.is_user_admin((SELECT auth.uid()))));

CREATE OR REPLACE FUNCTION public.platform_append_only() RETURNS trigger
    LANGUAGE plpgsql SET search_path = '' AS $$
BEGIN
    RAISE EXCEPTION '% is append-only', TG_TABLE_NAME USING ERRCODE = '42501';
END;
$$;
DROP TRIGGER IF EXISTS platform_audit_log_no_change ON public.platform_audit_log;
CREATE TRIGGER platform_audit_log_no_change BEFORE UPDATE OR DELETE ON public.platform_audit_log
    FOR EACH ROW EXECUTE FUNCTION public.platform_append_only();
DROP TRIGGER IF EXISTS platform_audit_log_no_truncate ON public.platform_audit_log;
CREATE TRIGGER platform_audit_log_no_truncate BEFORE TRUNCATE ON public.platform_audit_log
    FOR EACH STATEMENT EXECUTE FUNCTION public.platform_append_only();

-- ----------------------------------------------------------------------------
-- pod_tiers
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.pod_tiers (
    user_id     uuid PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
    tier        text NOT NULL CONSTRAINT pod_tiers_tier_shape CHECK (tier ~ '^[a-z][a-z0-9_-]{0,31}$'),
    assigned_by uuid REFERENCES auth.users(id) ON DELETE SET NULL,
    assigned_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    note        text CONSTRAINT pod_tiers_note_length CHECK (note IS NULL OR pg_catalog.char_length(note) <= 500)
);
ALTER TABLE public.pod_tiers ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.pod_tiers FROM PUBLIC, anon, authenticated;
GRANT SELECT, INSERT, DELETE ON public.pod_tiers TO authenticated;
GRANT UPDATE (tier, note) ON public.pod_tiers TO authenticated;
GRANT ALL ON public.pod_tiers TO service_role;
DROP POLICY IF EXISTS "pod_tiers admin select" ON public.pod_tiers;
CREATE POLICY "pod_tiers admin select" ON public.pod_tiers
    FOR SELECT TO authenticated USING ((SELECT public.is_user_admin((SELECT auth.uid()))));
DROP POLICY IF EXISTS "pod_tiers admin insert" ON public.pod_tiers;
CREATE POLICY "pod_tiers admin insert" ON public.pod_tiers
    FOR INSERT TO authenticated WITH CHECK ((SELECT public.is_user_admin((SELECT auth.uid()))));
DROP POLICY IF EXISTS "pod_tiers admin update" ON public.pod_tiers;
CREATE POLICY "pod_tiers admin update" ON public.pod_tiers
    FOR UPDATE TO authenticated USING ((SELECT public.is_user_admin((SELECT auth.uid()))))
    WITH CHECK ((SELECT public.is_user_admin((SELECT auth.uid()))));
DROP POLICY IF EXISTS "pod_tiers admin delete" ON public.pod_tiers;
CREATE POLICY "pod_tiers admin delete" ON public.pod_tiers
    FOR DELETE TO authenticated USING ((SELECT public.is_user_admin((SELECT auth.uid()))));

-- assigned_by / assigned_at are the server's: whoever writes, through any path.
CREATE OR REPLACE FUNCTION public.pod_tiers_stamp() RETURNS trigger
    LANGUAGE plpgsql SET search_path = '' AS $$
BEGIN
    NEW.assigned_by := public.agent_audit_actor();
    NEW.assigned_at := pg_catalog.clock_timestamp();
    NEW.note := NULLIF(pg_catalog.btrim(COALESCE(NEW.note, '')), '');
    RETURN NEW;
END;
$$;
DROP TRIGGER IF EXISTS pod_tiers_stamp ON public.pod_tiers;
CREATE TRIGGER pod_tiers_stamp BEFORE INSERT OR UPDATE ON public.pod_tiers
    FOR EACH ROW EXECUTE FUNCTION public.pod_tiers_stamp();

-- ----------------------------------------------------------------------------
-- pod_tier_history (append-only; no FK so the trail outlives the person)
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.pod_tier_history (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id       uuid NOT NULL,
    event         text NOT NULL CHECK (event IN ('assign', 'clear', 'applied')),
    -- assign: the new tier; clear: NULL (standard from now on); applied: what the pod now runs.
    tier          text,
    previous_tier text,
    actor         uuid,
    note          text,
    -- applied only: how it reached the pod.
    method        text CHECK (method IS NULL OR method IN ('in_place', 'restart', 'create', 'template')),
    at            timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX IF NOT EXISTS idx_pod_tier_history_user_at ON public.pod_tier_history (user_id, at DESC, id DESC);
CREATE INDEX IF NOT EXISTS idx_pod_tier_history_at ON public.pod_tier_history (at, id);
ALTER TABLE public.pod_tier_history ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.pod_tier_history FROM PUBLIC, anon, authenticated, service_role;
GRANT SELECT ON public.pod_tier_history TO authenticated, service_role;
DROP POLICY IF EXISTS "pod_tier_history admin select" ON public.pod_tier_history;
CREATE POLICY "pod_tier_history admin select" ON public.pod_tier_history
    FOR SELECT TO authenticated USING ((SELECT public.is_user_admin((SELECT auth.uid()))));
DROP TRIGGER IF EXISTS pod_tier_history_no_change ON public.pod_tier_history;
CREATE TRIGGER pod_tier_history_no_change BEFORE UPDATE OR DELETE ON public.pod_tier_history
    FOR EACH ROW EXECUTE FUNCTION public.platform_append_only();
DROP TRIGGER IF EXISTS pod_tier_history_no_truncate ON public.pod_tier_history;
CREATE TRIGGER pod_tier_history_no_truncate BEFORE TRUNCATE ON public.pod_tier_history
    FOR EACH STATEMENT EXECUTE FUNCTION public.platform_append_only();

-- Every change to pod_tiers, through any path (RLS writes, the RPCs below, the service role): history + audit.
-- A clear's note comes from the transaction setting pod_tiers.clear_note (platform_clear_pod_tier sets it).
CREATE OR REPLACE FUNCTION public.pod_tiers_audit() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    v_actor uuid := public.agent_audit_actor();
    v_note text;
BEGIN
    IF TG_OP = 'DELETE' THEN
        v_note := NULLIF(pg_catalog.current_setting('pod_tiers.clear_note', true), '');
        INSERT INTO public.pod_tier_history (user_id, event, tier, previous_tier, actor, note)
            VALUES (OLD.user_id, 'clear', NULL, OLD.tier, v_actor, v_note);
        INSERT INTO public.platform_audit_log (actor, action, target_user, detail)
            VALUES (v_actor, 'pod_tier.clear', OLD.user_id, pg_catalog.jsonb_build_object('previous_tier', OLD.tier));
        RETURN OLD;
    END IF;
    IF TG_OP = 'UPDATE' AND NEW.tier IS NOT DISTINCT FROM OLD.tier AND NEW.note IS NOT DISTINCT FROM OLD.note THEN
        RETURN NEW;
    END IF;
    INSERT INTO public.pod_tier_history (user_id, event, tier, previous_tier, actor, note)
        VALUES (NEW.user_id, 'assign', NEW.tier, CASE WHEN TG_OP = 'UPDATE' THEN OLD.tier END, v_actor, NEW.note);
    INSERT INTO public.platform_audit_log (actor, action, target_user, detail)
        VALUES (v_actor, 'pod_tier.assign', NEW.user_id, pg_catalog.jsonb_build_object(
            'tier', NEW.tier, 'previous_tier', CASE WHEN TG_OP = 'UPDATE' THEN OLD.tier END));
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION public.pod_tiers_audit() FROM PUBLIC, anon, authenticated;
DROP TRIGGER IF EXISTS pod_tiers_audit ON public.pod_tiers;
CREATE TRIGGER pod_tiers_audit AFTER INSERT OR UPDATE OR DELETE ON public.pod_tiers
    FOR EACH ROW EXECUTE FUNCTION public.pod_tiers_audit();

-- ----------------------------------------------------------------------------
-- The caller is a platform admin (chat's console API asks this with the caller's own session).
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.is_platform_admin() RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
    SELECT auth.uid() IS NOT NULL AND public.is_user_admin(auth.uid());
$$;
REVOKE ALL ON FUNCTION public.is_platform_admin() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.is_platform_admin() TO authenticated;

CREATE OR REPLACE FUNCTION public.require_platform_admin() RETURNS uuid
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    v uuid := auth.uid();
BEGIN
    IF v IS NULL OR NOT public.is_user_admin(v) THEN
        RAISE EXCEPTION 'Permission denied' USING ERRCODE = '42501';
    END IF;
    RETURN v;
END;
$$;
REVOKE ALL ON FUNCTION public.require_platform_admin() FROM PUBLIC, anon, authenticated;

-- ----------------------------------------------------------------------------
-- Console RPCs (platform admins; 42501 for everyone else).
-- ----------------------------------------------------------------------------

-- Assign (or change) a person's tier. P0001 user_not_found; 23514 tier_invalid / note_too_long.
CREATE OR REPLACE FUNCTION public.platform_set_pod_tier(p_user_id uuid, p_tier text, p_note text DEFAULT NULL)
RETURNS void LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    v_note text := NULLIF(pg_catalog.btrim(COALESCE(p_note, '')), '');
BEGIN
    PERFORM public.require_platform_admin();
    IF p_tier IS NULL OR p_tier !~ '^[a-z][a-z0-9_-]{0,31}$' THEN
        RAISE EXCEPTION 'tier_invalid' USING ERRCODE = '23514';
    END IF;
    IF pg_catalog.char_length(COALESCE(v_note, '')) > 500 THEN
        RAISE EXCEPTION 'note_too_long' USING ERRCODE = '23514';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM auth.users u WHERE u.id = p_user_id) THEN
        RAISE EXCEPTION 'user_not_found' USING ERRCODE = 'P0001';
    END IF;
    INSERT INTO public.pod_tiers (user_id, tier, note) VALUES (p_user_id, p_tier, v_note)
        ON CONFLICT (user_id) DO UPDATE SET tier = EXCLUDED.tier, note = EXCLUDED.note;
END;
$$;
REVOKE ALL ON FUNCTION public.platform_set_pod_tier(uuid, text, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.platform_set_pod_tier(uuid, text, text) TO authenticated;

-- Back to standard (deletes the row; the note goes into the history's clear entry).
CREATE OR REPLACE FUNCTION public.platform_clear_pod_tier(p_user_id uuid, p_note text DEFAULT NULL)
RETURNS boolean LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    v_note text := NULLIF(pg_catalog.btrim(COALESCE(p_note, '')), '');
    v_found boolean;
BEGIN
    PERFORM public.require_platform_admin();
    IF pg_catalog.char_length(COALESCE(v_note, '')) > 500 THEN
        RAISE EXCEPTION 'note_too_long' USING ERRCODE = '23514';
    END IF;
    PERFORM pg_catalog.set_config('pod_tiers.clear_note', COALESCE(v_note, ''), true);
    DELETE FROM public.pod_tiers t WHERE t.user_id = p_user_id;
    v_found := FOUND;
    PERFORM pg_catalog.set_config('pod_tiers.clear_note', '', true);
    RETURN v_found;
END;
$$;
REVOKE ALL ON FUNCTION public.platform_clear_pod_tier(uuid, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.platform_clear_pod_tier(uuid, text) TO authenticated;

-- People for the "People and pods" tab: with p_query, accounts whose email contains it; without, everyone with a
-- tier or an agent install. Service accounts (the provisioner bot) are never listed. Limit clamped to 1..200.
-- installs: [{agent_id, slug, name, state, instance_id, last_seen_at, running_version}] (no data_dir, no detail).
CREATE OR REPLACE FUNCTION public.platform_people_pods(p_query text DEFAULT NULL, p_limit int DEFAULT 50)
RETURNS TABLE (user_id uuid, email text, tier text, tier_note text, tier_assigned_at timestamptz,
               tier_assigned_by_email text, last_seen_at timestamptz, installs jsonb)
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    v_q text := pg_catalog.lower(NULLIF(pg_catalog.btrim(COALESCE(p_query, '')), ''));
    v_limit int := LEAST(GREATEST(COALESCE(p_limit, 50), 1), 200);
BEGIN
    PERFORM public.require_platform_admin();
    IF pg_catalog.char_length(COALESCE(v_q, '')) > 320 THEN
        RAISE EXCEPTION 'query_too_long' USING ERRCODE = '22023';
    END IF;
    RETURN QUERY
    WITH people AS (
        SELECT u.id, u.email::text AS email
          FROM auth.users u
         WHERE NOT EXISTS (SELECT 1 FROM public.agents_service_accounts s WHERE s.user_id = u.id)
           AND u.email IS NOT NULL
           AND (CASE WHEN v_q IS NOT NULL
                     THEN pg_catalog.strpos(pg_catalog.lower(u.email::text), v_q) > 0
                     ELSE EXISTS (SELECT 1 FROM public.pod_tiers t WHERE t.user_id = u.id)
                       OR EXISTS (SELECT 1 FROM public.agent_installs i WHERE i.user_id = u.id AND i.state <> 'removed')
                END)
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
    SELECT p.id, p.email, t.tier, t.note, t.assigned_at, ab.email::text, inst.last_seen_at,
           COALESCE(inst.installs, '[]'::jsonb)
      FROM people p
      LEFT JOIN public.pod_tiers t ON t.user_id = p.id
      LEFT JOIN auth.users ab ON ab.id = t.assigned_by
      LEFT JOIN inst ON inst.user_id = p.id
     ORDER BY inst.last_seen_at DESC NULLS LAST, p.email
     LIMIT v_limit;
END;
$$;
REVOKE ALL ON FUNCTION public.platform_people_pods(text, int) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.platform_people_pods(text, int) TO authenticated;

-- One person's tier history, newest first. Limit clamped to 1..200.
CREATE OR REPLACE FUNCTION public.platform_pod_tier_history(p_user_id uuid, p_limit int DEFAULT 50)
RETURNS TABLE (id bigint, event text, tier text, previous_tier text, actor_email text, note text, method text, at timestamptz)
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
BEGIN
    PERFORM public.require_platform_admin();
    RETURN QUERY
    SELECT h.id, h.event, h.tier, h.previous_tier, u.email::text, h.note, h.method, h.at
      FROM public.pod_tier_history h
      LEFT JOIN auth.users u ON u.id = h.actor
     WHERE h.user_id = p_user_id
     ORDER BY h.at DESC, h.id DESC
     LIMIT LEAST(GREATEST(COALESCE(p_limit, 50), 1), 200);
END;
$$;
REVOKE ALL ON FUNCTION public.platform_pod_tier_history(uuid, int) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.platform_pod_tier_history(uuid, int) TO authenticated;

-- ----------------------------------------------------------------------------
-- Provisioner bot (is_agents_provisioner): read assignments, report what it applied. Nothing else.
-- ----------------------------------------------------------------------------

-- p_since NULL: every current assignment. Otherwise every person whose assignment changed after p_since, with
-- their CURRENT tier (NULL = cleared, i.e. standard) and the time of the latest change. Ordered by that time.
-- Callers poll with an overlap (commits can land out of order) and resync in full now and then.
CREATE OR REPLACE FUNCTION public.pod_tier_assignments(p_since timestamptz DEFAULT NULL)
RETURNS TABLE (user_id uuid, tier text, changed_at timestamptz)
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
BEGIN
    IF NOT public.is_agents_provisioner() THEN
        RAISE EXCEPTION 'Permission denied' USING ERRCODE = '42501';
    END IF;
    IF p_since IS NULL THEN
        RETURN QUERY SELECT t.user_id, t.tier, t.assigned_at FROM public.pod_tiers t ORDER BY t.assigned_at, t.user_id;
        RETURN;
    END IF;
    RETURN QUERY
    SELECT c.user_id, t.tier, c.changed_at
      FROM (SELECT h.user_id, pg_catalog.max(h.at) AS changed_at
              FROM public.pod_tier_history h
             WHERE h.event IN ('assign', 'clear') AND h.at > p_since
             GROUP BY h.user_id) c
      LEFT JOIN public.pod_tiers t ON t.user_id = c.user_id
     ORDER BY c.changed_at, c.user_id;
END;
$$;
REVOKE ALL ON FUNCTION public.pod_tier_assignments(timestamptz) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.pod_tier_assignments(timestamptz) TO authenticated;

-- [{user_id, tier, method}] (at most 500): the tier each person's pod now runs. Appends an "applied" history row
-- and a platform audit entry per entry. Returns how many were recorded. P0001 invalid_applied on a bad entry.
CREATE OR REPLACE FUNCTION public.record_pod_tier_applied(p_entries jsonb)
RETURNS int LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    e jsonb;
    v_user uuid;
    v_tier text;
    v_method text;
    v_prev text;
    n int := 0;
BEGIN
    IF NOT public.is_agents_provisioner() THEN
        RAISE EXCEPTION 'Permission denied' USING ERRCODE = '42501';
    END IF;
    IF p_entries IS NULL OR pg_catalog.jsonb_typeof(p_entries) <> 'array' OR pg_catalog.jsonb_array_length(p_entries) > 500 THEN
        RAISE EXCEPTION 'invalid_applied' USING ERRCODE = 'P0001';
    END IF;
    FOR e IN SELECT * FROM pg_catalog.jsonb_array_elements(p_entries) LOOP
        BEGIN
            v_user := (e->>'user_id')::uuid;
        EXCEPTION WHEN invalid_text_representation THEN
            RAISE EXCEPTION 'invalid_applied' USING ERRCODE = 'P0001';
        END;
        v_tier := e->>'tier';
        v_method := e->>'method';
        IF v_user IS NULL OR v_tier IS NULL OR v_tier !~ '^[a-z][a-z0-9_-]{0,31}$'
           OR v_method IS NULL OR v_method NOT IN ('in_place', 'restart', 'create', 'template') THEN
            RAISE EXCEPTION 'invalid_applied' USING ERRCODE = 'P0001';
        END IF;
        SELECT h.tier INTO v_prev FROM public.pod_tier_history h
         WHERE h.user_id = v_user AND h.event = 'applied' ORDER BY h.at DESC, h.id DESC LIMIT 1;
        INSERT INTO public.pod_tier_history (user_id, event, tier, previous_tier, actor, method)
            VALUES (v_user, 'applied', v_tier, v_prev, NULL, v_method);
        INSERT INTO public.platform_audit_log (actor, action, target_user, detail)
            VALUES (NULL, 'pod_tier.applied', v_user, pg_catalog.jsonb_build_object('tier', v_tier, 'method', v_method));
        n := n + 1;
    END LOOP;
    RETURN n;
END;
$$;
REVOKE ALL ON FUNCTION public.record_pod_tier_applied(jsonb) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.record_pod_tier_applied(jsonb) TO authenticated;
