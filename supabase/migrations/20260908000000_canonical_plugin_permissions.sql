-- ============================================================================
-- Canonical Plugin Permissions: Enum and System Seed
-- ============================================================================
-- Defines the 12 canonical permissions required by BossConsole plugins:
--   network, filesystem, terminal, browser, notifications, auth, mcp,
--   editor, clipboard, settings, system, storage
--
-- Creates the plugin_permission_enum type, seeds the permissions into the
-- public.permissions table as protected system permissions, and guarantees
-- compatibility with plugins.required_permissions.
-- ============================================================================

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'plugin_permission_enum') THEN
        CREATE TYPE "public"."plugin_permission_enum" AS ENUM (
            'network',
            'filesystem',
            'terminal',
            'browser',
            'notifications',
            'auth',
            'mcp',
            'editor',
            'clipboard',
            'settings',
            'system',
            'storage'
        );
    END IF;
END $$;

COMMENT ON TYPE "public"."plugin_permission_enum" IS
    'The 12 canonical permission identifiers recognized by BossConsole plugin manifests';

-- Seed the canonical permissions into public.permissions as system permissions
INSERT INTO "public"."permissions" ("name", "description", "is_system")
VALUES
    ('network',       'Network access and outbound HTTP requests', true),
    ('filesystem',    'Local filesystem read and write access', true),
    ('terminal',      'Integrated terminal execution', true),
    ('browser',       'Integrated web browser controls', true),
    ('notifications', 'Desktop notifications and alerts', true),
    ('auth',          'Authentication and identity provider integration', true),
    ('mcp',           'Model Context Protocol tool discovery and execution', true),
    ('editor',        'Code editor and LSP integrations', true),
    ('clipboard',     'System clipboard read and write', true),
    ('settings',      'Application and workspace settings modification', true),
    ('system',        'System-level operations and OS bridge', true),
    ('storage',       'Local and cloud persistent key-value storage', true)
ON CONFLICT ("name") DO NOTHING;
