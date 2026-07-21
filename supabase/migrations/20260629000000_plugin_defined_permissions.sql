-- ============================================================================
-- Plugin-defined permissions: auto-registration at publish + provenance
-- ============================================================================
-- A plugin declares the NEW permissions it introduces in its manifest
-- (`definedPermissions`). When the plugin is published, the plugin-store edge
-- function (service role) calls register_plugin_permission() for each, which
-- inserts an UNGRANTED, non-system permission into the catalog. An admin then
-- grants it to roles via the normal assign_permission_to_role flow.
--
-- Safety (a plugin can never escalate itself):
--   * names must be `domain.action`, and the domain may NOT be a reserved system
--     domain (role, user, api_key, rpa, secret, plugins);
--   * permissions are always is_system = false;
--   * an existing system permission is never touched / never re-owned;
--   * registration NEVER grants the permission to any role.
-- ============================================================================

-- Provenance: which plugin defined which (non-system) permission. Keyed on the
-- permission id so deleting the permission cascades the provenance away.
CREATE TABLE IF NOT EXISTS "public"."plugin_permissions" (
    "permission_id" "uuid" PRIMARY KEY REFERENCES "public"."permissions"("id") ON DELETE CASCADE,
    "plugin_id" "text" NOT NULL,
    "created_at" timestamp with time zone NOT NULL DEFAULT "now"()
);

CREATE INDEX IF NOT EXISTS "idx_plugin_permissions_plugin_id"
    ON "public"."plugin_permissions" ("plugin_id");

-- Readable by anyone who can read roles/permissions (the role-management UI),
-- so admins can see "defined by <plugin>" + whether it's granted yet.
ALTER TABLE "public"."plugin_permissions" ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "role.read can view plugin permission provenance" ON "public"."plugin_permissions";
CREATE POLICY "role.read can view plugin permission provenance"
    ON "public"."plugin_permissions" FOR SELECT TO "authenticated"
    USING (public.authorize('role.read'));

GRANT SELECT ON "public"."plugin_permissions" TO "authenticated";

-- ----------------------------------------------------------------------------
-- register_plugin_permission(name, description, plugin_id)
-- Called by the plugin-store edge function (service role) at publish time.
-- Idempotent. Returns jsonb { success, ... }.
-- ----------------------------------------------------------------------------
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
    -- Format: domain.action (mirror create_new_permission)
    IF p_name IS NULL OR NOT (p_name ~ '^[a-z][a-z0-9_]{1,30}\.[a-z][a-z0-9_]{1,30}$') THEN
        RETURN jsonb_build_object('success', false, 'error', 'Invalid permission format');
    END IF;

    -- Reserved system domains are off-limits to plugin-defined permissions.
    v_domain := split_part(p_name, '.', 1);
    IF v_domain IN ('role', 'user', 'api_key', 'rpa', 'secret', 'plugins') THEN
        RETURN jsonb_build_object('success', false, 'error',
            format('Reserved permission domain "%s"', v_domain));
    END IF;

    SELECT id, is_system INTO v_existing_id, v_existing_is_system
    FROM public.permissions WHERE name = p_name;

    IF FOUND THEN
        -- Never touch / re-own a core (system) permission.
        IF v_existing_is_system THEN
            RETURN jsonb_build_object('success', false, 'error',
                format('Permission "%s" is a system permission', p_name));
        END IF;
        -- Already registered as a plugin permission: ensure provenance, idempotently.
        INSERT INTO public.plugin_permissions (permission_id, plugin_id)
        VALUES (v_existing_id, p_plugin_id)
        ON CONFLICT (permission_id) DO NOTHING;
        RETURN jsonb_build_object('success', true, 'created', false,
            'permission_id', v_existing_id::text, 'permission', p_name);
    END IF;

    -- New permission: non-system, UNGRANTED.
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

-- Only the edge function (service role) may register; end users never call this
-- directly (they create permissions via create_new_permission, gated role.create).
REVOKE EXECUTE ON FUNCTION "public"."register_plugin_permission"("text", "text", "text") FROM PUBLIC, "anon", "authenticated";
GRANT EXECUTE ON FUNCTION "public"."register_plugin_permission"("text", "text", "text") TO "service_role";
