-- Marketplace reviewer: a global role that can read the review queue and approve or reject a pending version, and nothing else.
-- Suspend, trusted publishers, mark-reviewed, agent default skills, categories and metadata stay admin-only.
-- marketplace_get and the v2 bundle read are not redefined here: the queue already carries each pending version in full.

INSERT INTO public.roles (name, description, is_system) VALUES
    ('marketplace_reviewer', 'Reviews marketplace submissions: reads the pending queue and approves or rejects versions. No other admin powers.', true)
ON CONFLICT (name) DO NOTHING;

INSERT INTO public.role_hierarchy (parent_role_id, child_role_id)
SELECT parent.id, child.id FROM public.roles parent, public.roles child
 WHERE parent.name = 'admin' AND child.name = 'marketplace_reviewer'
ON CONFLICT DO NOTHING;

-- Admin or reviewer.
CREATE OR REPLACE FUNCTION public.is_marketplace_reviewer(p_user_id uuid) RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
    SELECT p_user_id IS NOT NULL AND (public.is_user_admin(p_user_id) OR EXISTS (
        SELECT 1 FROM public.user_roles ur JOIN public.roles r ON r.id = ur.role_id
         WHERE ur.user_id = p_user_id AND r.name = 'marketplace_reviewer'));
$$;
REVOKE ALL ON FUNCTION public.is_marketplace_reviewer(uuid) FROM PUBLIC, anon, authenticated;

-- For the console: may the caller approve or reject?
CREATE OR REPLACE FUNCTION public.marketplace_review_access() RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
    SELECT public.is_marketplace_reviewer(auth.uid());
$$;
REVOKE ALL ON FUNCTION public.marketplace_review_access() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.marketplace_review_access() TO authenticated;

-- Grants or revokes the reviewer role by email. Admins only; audited. Returns whether the person now has the role.
CREATE OR REPLACE FUNCTION public.platform_set_marketplace_reviewer(p_email text, p_grant boolean)
RETURNS boolean
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    v_actor uuid := public.require_platform_admin();
    v_email text := pg_catalog.lower(pg_catalog.btrim(COALESCE(p_email, '')));
    v_user uuid;
    v_role uuid;
    v_changed int;
BEGIN
    IF v_email = '' OR pg_catalog.char_length(v_email) > 320 OR p_grant IS NULL THEN
        RAISE EXCEPTION 'bad_input' USING ERRCODE = '22023';
    END IF;
    SELECT u.id INTO v_user FROM auth.users u WHERE pg_catalog.lower(u.email::text) = v_email AND u.deleted_at IS NULL;
    IF v_user IS NULL THEN
        RAISE EXCEPTION 'user_not_found' USING ERRCODE = 'P0002';
    END IF;
    SELECT r.id INTO v_role FROM public.roles r WHERE r.name = 'marketplace_reviewer';
    IF p_grant THEN
        INSERT INTO public.user_roles (user_id, role_id, assigned_by) VALUES (v_user, v_role, v_actor)
            ON CONFLICT (user_id, role_id) DO NOTHING;
    ELSE
        DELETE FROM public.user_roles ur WHERE ur.user_id = v_user AND ur.role_id = v_role;
    END IF;
    GET DIAGNOSTICS v_changed = ROW_COUNT;
    IF v_changed > 0 THEN
        INSERT INTO public.platform_audit_log (actor, action, target_user, detail)
            VALUES (v_actor, CASE WHEN p_grant THEN 'platform_role.marketplace_reviewer_grant' ELSE 'platform_role.marketplace_reviewer_revoke' END,
                    v_user, '{}'::jsonb);
    END IF;
    RETURN EXISTS (SELECT 1 FROM public.user_roles ur WHERE ur.user_id = v_user AND ur.role_id = v_role);
END;
$$;
REVOKE ALL ON FUNCTION public.platform_set_marketplace_reviewer(text, boolean) FROM PUBLIC, anon, service_role;
GRANT EXECUTE ON FUNCTION public.platform_set_marketplace_reviewer(text, boolean) TO authenticated;

-- marketplace_pending: body from 20261030160000_marketplace_trust_defaults.sql; only the guard changes.
CREATE OR REPLACE FUNCTION public.marketplace_pending()
RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
BEGIN
    IF auth.uid() IS NULL OR NOT (public.is_platform_reader(auth.uid()) OR public.is_marketplace_reviewer(auth.uid())) THEN
        RAISE EXCEPTION 'Permission denied' USING ERRCODE = '42501';
    END IF;
    RETURN COALESCE((
        SELECT pg_catalog.jsonb_agg(pg_catalog.jsonb_strip_nulls(pg_catalog.jsonb_build_object(
                   'slug', l.slug, 'kind', l.kind, 'status', l.status, 'publisher_email', u.email,
                   'pending', public.marketplace_version_json(pv, true),
                   'reviewed', CASE WHEN lv.id IS NOT NULL THEN lv.decision = 'approved' END,
                   'live', CASE WHEN lv.id IS NOT NULL THEN public.marketplace_version_json(lv, true) END))
               ORDER BY pv.created_at)
        FROM public.marketplace_listings l
        JOIN public.marketplace_versions pv ON pv.id = l.pending_version_id
        LEFT JOIN public.marketplace_versions lv ON lv.id = l.live_version_id
        LEFT JOIN auth.users u ON u.id = l.publisher_id
        WHERE l.status <> 'suspended'), '[]'::jsonb);
END;
$$;

-- marketplace_review: body from 20261030150000_marketplace.sql; only the guard changes.
CREATE OR REPLACE FUNCTION public.marketplace_review(p_version_id uuid, p_approve boolean, p_reason text DEFAULT NULL)
RETURNS public.marketplace_status LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    note text := NULLIF(pg_catalog.btrim(COALESCE(p_reason, '')), '');
    v public.marketplace_versions%ROWTYPE;
    l public.marketplace_listings%ROWTYPE;
    new_status public.marketplace_status;
BEGIN
    IF auth.uid() IS NULL OR NOT public.is_marketplace_reviewer(auth.uid()) THEN
        RAISE EXCEPTION 'Permission denied' USING ERRCODE = '42501';
    END IF;
    IF NOT p_approve AND note IS NULL THEN
        RAISE EXCEPTION 'reason_required' USING ERRCODE = 'P0001';
    END IF;
    IF pg_catalog.char_length(COALESCE(note, '')) > 500 THEN
        RAISE EXCEPTION 'reason_too_long' USING ERRCODE = 'P0001';
    END IF;
    SELECT * INTO v FROM public.marketplace_versions WHERE id = p_version_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'version_not_found' USING ERRCODE = 'P0001';
    END IF;
    SELECT * INTO l FROM public.marketplace_listings WHERE id = v.listing_id FOR UPDATE;
    IF l.pending_version_id IS DISTINCT FROM v.id OR l.status = 'suspended' THEN
        RAISE EXCEPTION 'version_not_pending_review' USING ERRCODE = 'P0001';
    END IF;
    UPDATE public.marketplace_versions
        SET decision = CASE WHEN p_approve THEN 'approved' ELSE 'rejected' END,
            decided_by = auth.uid(), decided_at = now(), reason = note
        WHERE id = v.id;
    IF p_approve THEN
        new_status := 'live';
        UPDATE public.marketplace_listings SET live_version_id = v.id, pending_version_id = NULL, status = 'live',
            review_note = NULL, updated_at = now() WHERE id = l.id;
    ELSE
        new_status := CASE WHEN l.live_version_id IS NOT NULL AND l.status = 'live' THEN 'live' ELSE 'rejected' END;
        UPDATE public.marketplace_listings SET pending_version_id = NULL, status = new_status, review_note = note,
            updated_at = now() WHERE id = l.id;
    END IF;
    INSERT INTO public.platform_audit_log (actor, action, target_user, detail)
        VALUES (auth.uid(), CASE WHEN p_approve THEN 'marketplace.approve' ELSE 'marketplace.reject' END, l.publisher_id,
                pg_catalog.jsonb_strip_nulls(pg_catalog.jsonb_build_object('slug', l.slug, 'version', v.version, 'reason', note)));
    RETURN new_status;
END;
$$;
