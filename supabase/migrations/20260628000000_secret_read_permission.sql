-- ============================================================================
-- secret.read permission for the Secret Manager plugin
-- ============================================================================
-- The Secret Manager is a per-user vault (its RPCs scope to auth.uid()), but its
-- plugin panel was historically gated by the legacy `requiresAdmin` flag (admin
-- role only). To let admin tiers — specifically boss_admin — see/use it under the
-- granular RBAC model, the plugin now declares requiredPermissions: ["secret.read"].
-- This migration creates that permission and grants it to admin + boss_admin.
-- (admin also passes authorize() via the is_user_admin short-circuit; the explicit
-- grant keeps the effective-permission JWT claim accurate.)
-- Idempotent and non-destructive.
-- ============================================================================

INSERT INTO "public"."permissions" ("name", "description", "is_system")
VALUES ('secret.read', 'Access the Secret Manager (own encrypted secret vault)', true)
ON CONFLICT ("name") DO NOTHING;

INSERT INTO "public"."role_permissions" ("role_id", "permission_id")
SELECT r."id", p."id"
FROM (VALUES ('admin'), ('boss_admin')) AS grant_map("role_name")
JOIN "public"."roles" r ON r."name" = grant_map."role_name"
JOIN "public"."permissions" p ON p."name" = 'secret.read'
ON CONFLICT ("role_id", "permission_id") DO NOTHING;
