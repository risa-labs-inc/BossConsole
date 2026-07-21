-- ============================================================================
-- BOSS Database Schema: Plugin Store Admin Permissions & Policies
-- ============================================================================
-- File: 20260131000000_plugin_store_admin_policies.sql
-- Description: Add plugin management permissions and RLS policies.
--              Uses existing RBAC system (permissions table + authorize function).
-- Dependencies:
--   - 20251023000002_rbac_functions.sql (authorize function)
--   - 20251023000008_rbac_tables.sql (permissions, role_permissions tables)
--   - 20260130000000_plugin_store_tables.sql
-- ============================================================================


-- ============================================================================
-- SECTION 1: Create Plugin Admin Permissions
-- ============================================================================

-- Permission to delete any plugin
INSERT INTO permissions (name, description, is_system)
VALUES ('plugins.admin.delete', 'Can delete any plugin from the store', true)
ON CONFLICT (name) DO NOTHING;

-- Permission to enable/disable any plugin
INSERT INTO permissions (name, description, is_system)
VALUES ('plugins.admin.publish', 'Can enable/disable any plugin in the store', true)
ON CONFLICT (name) DO NOTHING;

-- Permission to verify/unverify any plugin
INSERT INTO permissions (name, description, is_system)
VALUES ('plugins.admin.verify', 'Can verify/unverify any plugin in the store', true)
ON CONFLICT (name) DO NOTHING;

-- Permission to view all plugins (including unpublished)
INSERT INTO permissions (name, description, is_system)
VALUES ('plugins.admin.view', 'Can view all plugins including unpublished', true)
ON CONFLICT (name) DO NOTHING;


-- ============================================================================
-- SECTION 2: Assign Permissions to Admin Role
-- ============================================================================

-- Assign all plugin admin permissions to admin role
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r, permissions p
WHERE r.name = 'admin'
AND p.name IN ('plugins.admin.delete', 'plugins.admin.publish', 'plugins.admin.verify', 'plugins.admin.view')
ON CONFLICT DO NOTHING;


-- ============================================================================
-- SECTION 3: Admin RLS Policies for plugins table
-- ============================================================================

-- Admins can view ALL plugins (including unpublished)
CREATE POLICY "Users with plugins.admin.view can view all plugins"
    ON plugins FOR SELECT
    USING (authorize('plugins.admin.view'));

-- Admins can update ANY plugin (for publish/verify status)
CREATE POLICY "Users with plugins.admin.publish can update any plugin"
    ON plugins FOR UPDATE
    USING (authorize('plugins.admin.publish'));

-- Admins can delete ANY plugin
CREATE POLICY "Users with plugins.admin.delete can delete any plugin"
    ON plugins FOR DELETE
    USING (authorize('plugins.admin.delete'));


-- ============================================================================
-- SECTION 4: Admin RLS Policies for related tables
-- ============================================================================

-- Admins can view all plugin versions
CREATE POLICY "Users with plugins.admin.view can view all versions"
    ON plugin_versions FOR SELECT
    USING (authorize('plugins.admin.view'));

-- Admins can delete plugin versions
CREATE POLICY "Users with plugins.admin.delete can delete versions"
    ON plugin_versions FOR DELETE
    USING (authorize('plugins.admin.delete'));

-- Admins can view all tags
CREATE POLICY "Users with plugins.admin.view can view all tags"
    ON plugin_tags FOR SELECT
    USING (authorize('plugins.admin.view'));

-- Admins can manage all tags
CREATE POLICY "Users with plugins.admin.delete can manage all tags"
    ON plugin_tags FOR ALL
    USING (authorize('plugins.admin.delete'));

-- Admins can view all screenshots
CREATE POLICY "Users with plugins.admin.view can view all screenshots"
    ON plugin_screenshots FOR SELECT
    USING (authorize('plugins.admin.view'));

-- Admins can manage all screenshots
CREATE POLICY "Users with plugins.admin.delete can manage all screenshots"
    ON plugin_screenshots FOR ALL
    USING (authorize('plugins.admin.delete'));
