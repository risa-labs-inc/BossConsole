-- An organisation admin may not grant ai.* to an organisation role.
--
-- ai.* permissions gate BOSS AI models and their token allowances (boss_ai_policy). The domain
-- cannot join is_org_grantable_permission's deny-list: that guard binds service_role too, and the
-- Optimist organisation's user role must carry ai.optimist. Without this check any holder who also
-- administers another organisation could hand that organisation the model, since the only
-- remaining gate is "you cannot grant what you do not hold". Platform admins still may.
BEGIN;
CREATE OR REPLACE FUNCTION "public"."grant_organisation_role_permission"(
    "p_org_id" "uuid",
    "p_role_id" "uuid",
    "p_permission_name" "text",
    "p_actor_id" "uuid" DEFAULT NULL::"uuid"
) RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
DECLARE
    v_actor UUID;
    v_permission_id UUID;
BEGIN
    v_actor := public.resolve_org_actor(p_actor_id);
    IF v_actor IS NULL THEN
        RETURN jsonb_build_object('success', false, 'error', 'Not authenticated');
    END IF;

    IF NOT public.user_is_org_admin(v_actor, p_org_id) THEN
        RETURN jsonb_build_object('success', false, 'error', 'Permission denied');
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM public.organisation_roles orl
        WHERE orl.org_id = p_org_id AND orl.role_id = p_role_id
    ) THEN
        RETURN jsonb_build_object('success', false, 'error',
            'That role does not belong to this organisation');
    END IF;

    SELECT p.id INTO v_permission_id
    FROM public.permissions p WHERE p.name = p_permission_name;
    IF NOT FOUND THEN
        RETURN jsonb_build_object('success', false, 'error',
            format('Permission "%s" not found', p_permission_name));
    END IF;

    IF NOT public.is_org_grantable_permission(v_permission_id) THEN
        RETURN jsonb_build_object('success', false, 'error',
            format('Permission "%s" cannot be granted to an organisation role', p_permission_name));
    END IF;

    -- ai.* gates BOSS AI models and spend; only a platform admin extends it to an organisation.
    IF split_part(p_permission_name, '.', 1) = 'ai' AND NOT public.is_user_admin(v_actor) THEN
        RETURN jsonb_build_object('success', false, 'error',
            format('Permission "%s" can only be granted by a platform administrator', p_permission_name));
    END IF;

    -- Mirrors assign_permission_to_role: you cannot grant what you do not hold.
    IF NOT public.user_holds_permission(v_actor, p_permission_name) THEN
        RETURN jsonb_build_object('success', false, 'error',
            format('Cannot grant a permission you do not hold ("%s")', p_permission_name));
    END IF;

    -- enforce_org_role_permission_scope is the belt behind this brace.
    INSERT INTO public.role_permissions (role_id, permission_id)
    VALUES (p_role_id, v_permission_id)
    ON CONFLICT (role_id, permission_id) DO NOTHING;

    RETURN jsonb_build_object('success', true);
END;
$$;

ALTER FUNCTION "public"."grant_organisation_role_permission"("uuid", "uuid", "text", "uuid") OWNER TO "postgres";

REVOKE EXECUTE ON FUNCTION "public"."grant_organisation_role_permission"("uuid", "uuid", "text", "uuid") FROM PUBLIC, "anon";
GRANT EXECUTE ON FUNCTION "public"."grant_organisation_role_permission"("uuid", "uuid", "text", "uuid") TO "authenticated", "service_role";
COMMIT;
