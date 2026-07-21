-- ============================================================================
-- BOSS Database Schema: RBAC (Role-Based Access Control) Tables
-- ============================================================================
-- File: 20251023000008_rbac_tables.sql
-- Description: Table-based RBAC system with roles, permissions, and user
--              assignments. Replaces enum-based RBAC with flexible table-based
--              approach supporting full CRUD operations on roles/permissions.
-- Dependencies:
--   - File 1: extensions_and_types.sql (uuid-ossp extension)
--   - auth.users table (Supabase Auth)
-- Tables: 5 total
-- Architecture: Flexible RBAC with system role/permission protection
-- Migration Context: Table-based replacement for app_role/app_permission enums
-- ============================================================================


-- ============================================================================
-- RBAC Architecture Overview
-- ============================================================================
--
-- This schema implements a flexible table-based RBAC system that allows:
--   1. Dynamic role creation/deletion (admin interface)
--   2. Dynamic permission creation/deletion (admin interface)
--   3. Role-to-permission assignments (many-to-many via role_permissions)
--   4. User-to-role assignments (many-to-many via user_roles)
--   5. System role/permission protection (is_system flag prevents deletion)
--
-- Table Relationships:
--   users ←→ user_roles ←→ roles ←→ role_permissions ←→ permissions
--
-- Example Data Flow:
--   1. User "alice@example.com" created → inserted into users table
--   2. Role "editor" created → inserted into roles table
--   3. Permission "posts.edit" created → inserted into permissions table
--   4. Link role to permission → inserted into role_permissions
--   5. Assign role to user → inserted into user_roles
--   6. Result: Alice can edit posts
--
-- System Roles (is_system = true, cannot be deleted):
--   - user: Default role assigned to all new users
--   - admin: Full system access, can manage roles/permissions
--
-- Permission Naming Convention:
--   Format: "domain.action"
--   Examples: "users.read", "posts.edit", "secrets.delete"
--
-- ============================================================================


-- ============================================================================
-- SECTION 1: Base Tables (2 tables)
-- ============================================================================
-- Purpose: Foundation tables - users and roles


-- Table 1.1: users
-- -----------------------------------------------------------------------------
-- Purpose: Application user data, synchronized from auth.users
-- Why Needed: Mirror of Supabase Auth users for:
--   - Foreign key relationships (secrets, user_roles, etc.)
--   - RLS policy checks (user_id = auth.uid())
--   - Application-specific user metadata
--
-- Synchronization:
--   - Created by handle_new_user() trigger on auth.users INSERT
--   - Updated by handle_user_email_update() trigger on auth.users UPDATE
--   - auth.users is source of truth for authentication
--   - public.users is source of truth for application data
--
-- Columns:
--   - id: User UUID (matches auth.users.id, PRIMARY KEY)
--   - email: User email address (synced from auth.users.email)
--   - created_at: When user record was created (NOT when user signed up)
--   - updated_at: Last modification timestamp (updated on email change)
--
-- Relationships:
--   - ONE user → MANY user_roles (user can have multiple roles)
--   - ONE user → MANY secrets (user can own multiple secrets)
--   - ONE user → MANY user_passkeys (user can register multiple passkeys)
--
-- Data Lifecycle:
--   1. User signs up → auth.users INSERT → trigger → public.users INSERT
--   2. User changes email → auth.users UPDATE → trigger → public.users UPDATE
--   3. User deleted (admin) → public.users DELETE → cascade to user_roles, secrets
--   4. Auth user deletion (separate) → must call Supabase Auth API
--
-- Important Notes:
--   - id is NOT auto-generated (copied from auth.users.id)
--   - email is NOT UNIQUE constraint (handled by auth.users)
--   - Deletion cascades to related records (user_roles, secrets, etc.)
--
-- Usage:
--   -- Query user by ID
--   SELECT * FROM users WHERE id = auth.uid();
--
--   -- Count total users
--   SELECT COUNT(*) FROM users;
CREATE TABLE IF NOT EXISTS "public"."users" (
    "id" "uuid" NOT NULL,
    "email" "text" NOT NULL,
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "updated_at" timestamp with time zone DEFAULT "now"() NOT NULL
);

ALTER TABLE "public"."users" OWNER TO "postgres";

COMMENT ON TABLE "public"."users" IS 'Application user data synced from auth.users';



-- Table 1.2: roles
-- -----------------------------------------------------------------------------
-- Purpose: Role definitions (e.g., admin, editor, viewer)
-- Why Table-Based: Replaced enum-based RBAC to support:
--   - Dynamic role creation via admin interface
--   - Flexible role management (create, update, delete)
--   - System role protection (is_system flag prevents deletion)
--
-- System Roles (is_system = true):
--   - "user": Default role for all new users (assigned by handle_new_user trigger)
--   - "admin": Full system access (bypasses most RLS policies)
--
-- Columns:
--   - id: Role UUID (PRIMARY KEY, auto-generated via gen_random_uuid)
--   - name: Unique role name (e.g., "admin", "editor", "viewer")
--   - description: Optional human-readable description
--   - is_system: Protection flag (true = cannot be deleted, false = deletable)
--   - created_at: When role was created
--   - updated_at: Last modification timestamp
--
-- Relationships:
--   - ONE role → MANY user_roles (role can be assigned to many users)
--   - ONE role → MANY role_permissions (role can have many permissions)
--   - ONE role → MANY secret_shares (secrets can be shared with roles)
--
-- Role Lifecycle:
--   1. Create role → INSERT INTO roles (name, description, is_system=false)
--   2. Assign permissions → INSERT INTO role_permissions (role_id, permission_id)
--   3. Assign to users → INSERT INTO user_roles (user_id, role_id)
--   4. Delete role (if not system) → DELETE FROM roles (cascades to role_permissions, user_roles)
--
-- System Role Protection:
--   - delete_role() function checks is_system flag
--   - Raises exception if attempting to delete system role
--   - Ensures "user" and "admin" roles always exist
--
-- Naming Conventions:
--   - Use lowercase (e.g., "admin", not "Admin")
--   - Use singular nouns (e.g., "editor", not "editors")
--   - Keep names short and descriptive
--
-- Usage:
--   -- Create new role
--   INSERT INTO roles (name, description) VALUES ('moderator', 'Can moderate content');
--
--   -- List all roles
--   SELECT * FROM roles ORDER BY name;
--
--   -- Check if role is system-protected
--   SELECT is_system FROM roles WHERE name = 'admin';  -- Returns: true
CREATE TABLE IF NOT EXISTS "public"."roles" (
    "id" "uuid" DEFAULT "gen_random_uuid"() NOT NULL,
    "name" "text" NOT NULL,
    "description" "text",
    "is_system" boolean DEFAULT false NOT NULL,
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "updated_at" timestamp with time zone DEFAULT "now"() NOT NULL
);

ALTER TABLE "public"."roles" OWNER TO "postgres";

COMMENT ON TABLE "public"."roles" IS 'Application roles (table-based replacement for app_role enum). Supports full CRUD operations with system role protection.';

COMMENT ON COLUMN "public"."roles"."name" IS 'Unique role name (e.g., "user", "admin", "developer")';

COMMENT ON COLUMN "public"."roles"."description" IS 'Optional human-readable description';

COMMENT ON COLUMN "public"."roles"."is_system" IS 'System roles (user, admin) cannot be deleted';



-- ============================================================================
-- SECTION 2: Permission Tables (1 table)
-- ============================================================================
-- Purpose: Define granular permissions (domain.action format)


-- Table 2.1: permissions
-- -----------------------------------------------------------------------------
-- Purpose: Permission definitions (e.g., users.read, posts.edit)
-- Why Table-Based: Replaced enum-based permissions to support:
--   - Dynamic permission creation via admin interface
--   - Fine-grained access control (domain.action format)
--   - System permission protection (is_system flag prevents deletion)
--
-- System Permissions (is_system = true):
--   Examples: "users.read", "roles.create", "secrets.delete"
--   These core permissions should not be deletable to maintain security
--
-- Permission Naming Convention:
--   Format: "domain.action"
--   Domain: Area of application (users, roles, secrets, posts)
--   Action: Operation (read, create, edit, delete, share)
--   Examples:
--     - "users.read" → Can view user list
--     - "posts.edit" → Can edit posts
--     - "secrets.delete" → Can delete secrets
--     - "roles.create" → Can create new roles
--
-- Columns:
--   - id: Permission UUID (PRIMARY KEY, auto-generated via gen_random_uuid)
--   - name: Unique permission name in domain.action format
--   - description: Optional human-readable description
--   - is_system: Protection flag (true = cannot be deleted, false = deletable)
--   - created_at: When permission was created
--   - updated_at: Last modification timestamp
--
-- Relationships:
--   - ONE permission → MANY role_permissions (permission can be assigned to many roles)
--
-- Permission Lifecycle:
--   1. Create permission → INSERT INTO permissions (name, description, is_system=false)
--   2. Assign to roles → INSERT INTO role_permissions (role_id, permission_id)
--   3. Check permission → authorize('permission.name') function
--   4. Delete permission (if not system) → DELETE FROM permissions (cascades to role_permissions)
--
-- System Permission Protection:
--   - delete_permission() function checks is_system flag
--   - Raises exception if attempting to delete system permission
--   - Ensures core permissions always exist
--
-- RLS Policy Usage:
--   -- Policy example: Only users with "secrets.delete" can delete secrets
--   CREATE POLICY "Authorized users can delete secrets"
--     ON secrets FOR DELETE
--     USING (authorize('secrets.delete'));
--
-- Common Permission Patterns:
--   - CRUD operations: "domain.create", "domain.read", "domain.update", "domain.delete"
--   - Special actions: "secrets.share", "users.impersonate", "roles.assign"
--   - Admin bypass: "admin.*" (not implemented, use admin role instead)
--
-- Usage:
--   -- Create new permission
--   INSERT INTO permissions (name, description)
--   VALUES ('posts.publish', 'Can publish posts to production');
--
--   -- List all permissions
--   SELECT * FROM permissions ORDER BY name;
--
--   -- Check if permission is system-protected
--   SELECT is_system FROM permissions WHERE name = 'users.read';  -- Returns: true
CREATE TABLE IF NOT EXISTS "public"."permissions" (
    "id" "uuid" DEFAULT "gen_random_uuid"() NOT NULL,
    "name" "text" NOT NULL,
    "description" "text",
    "is_system" boolean DEFAULT false NOT NULL,
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "updated_at" timestamp with time zone DEFAULT "now"() NOT NULL
);

ALTER TABLE "public"."permissions" OWNER TO "postgres";

COMMENT ON TABLE "public"."permissions" IS 'Application permissions (table-based replacement for app_permission enum). Supports full CRUD operations with system permission protection.';

COMMENT ON COLUMN "public"."permissions"."name" IS 'Unique permission name in domain.action format (e.g., "users.read")';

COMMENT ON COLUMN "public"."permissions"."description" IS 'Optional human-readable description';

COMMENT ON COLUMN "public"."permissions"."is_system" IS 'System permissions cannot be deleted';



-- ============================================================================
-- SECTION 3: Many-to-Many Join Tables (2 tables)
-- ============================================================================
-- Purpose: Link users to roles and roles to permissions


-- Table 3.1: user_roles
-- -----------------------------------------------------------------------------
-- Purpose: Assign roles to users (many-to-many relationship)
-- Why Needed: Users can have multiple roles (e.g., admin + editor)
--
-- Design Pattern: Many-to-Many Join Table
--   users ←→ user_roles ←→ roles
--   One user can have many roles
--   One role can be assigned to many users
--
-- Columns:
--   - id: Assignment UUID (PRIMARY KEY, auto-generated via gen_random_uuid)
--   - user_id: Foreign key to users table (who has the role)
--   - role_id: Foreign key to roles table (which role is assigned)
--   - assigned_by: UUID of admin who assigned role (NULL for system assignments)
--   - assigned_at: When role was assigned (audit trail)
--   - created_at: Record creation timestamp
--
-- Constraints:
--   - UNIQUE(user_id, role_id): User can't have same role twice
--   - FOREIGN KEY user_id → users.id (CASCADE DELETE)
--   - FOREIGN KEY role_id → roles.id (CASCADE DELETE)
--
-- Default Role Assignment:
--   - handle_new_user() trigger assigns "user" role to all new users
--   - assigned_by = NULL (system assignment, not manual admin assignment)
--
-- Assignment Types:
--   1. System Assignment (assigned_by = NULL):
--      - Default "user" role on signup
--      - Automated role assignments
--   2. Manual Assignment (assigned_by = admin_uuid):
--      - Admin assigns role via assign_role_to_user() function
--      - Audit trail shows who assigned the role
--
-- Role Removal:
--   - remove_role_from_user() function: DELETE FROM user_roles
--   - Cascading deletion: If role deleted, all user_roles entries cascade delete
--
-- Query Patterns:
--   -- Get all roles for a user
--   SELECT r.name FROM user_roles ur
--   JOIN roles r ON r.id = ur.role_id
--   WHERE ur.user_id = '...';
--
--   -- Get all users with a specific role
--   SELECT u.email FROM user_roles ur
--   JOIN users u ON u.id = ur.user_id
--   WHERE ur.role_id = (SELECT id FROM roles WHERE name = 'admin');
--
-- Usage:
--   -- Assign role to user (via function)
--   SELECT assign_role_to_user('<user-uuid>', 'editor');
--
--   -- Remove role from user (via function)
--   SELECT remove_role_from_user('<user-uuid>', 'editor');
CREATE TABLE IF NOT EXISTS "public"."user_roles" (
    "id" "uuid" DEFAULT "gen_random_uuid"() NOT NULL,
    "user_id" "uuid" NOT NULL,
    "role_id" "uuid" NOT NULL,
    "assigned_by" "uuid",
    "assigned_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL
);

ALTER TABLE "public"."user_roles" OWNER TO "postgres";

COMMENT ON TABLE "public"."user_roles" IS 'Maps users to roles (NEW table-based version). Will replace user_roles table.';

COMMENT ON COLUMN "public"."user_roles"."role_id" IS 'Foreign key to roles table (replaces role enum)';



-- Table 3.2: role_permissions
-- -----------------------------------------------------------------------------
-- Purpose: Assign permissions to roles (many-to-many relationship)
-- Why Needed: Roles can have multiple permissions, permissions can belong to multiple roles
--
-- Design Pattern: Many-to-Many Join Table
--   roles ←→ role_permissions ←→ permissions
--   One role can have many permissions
--   One permission can be assigned to many roles
--
-- Columns:
--   - id: Assignment UUID (PRIMARY KEY, auto-generated via gen_random_uuid)
--   - role_id: Foreign key to roles table (which role has the permission)
--   - permission_id: Foreign key to permissions table (which permission is granted)
--   - created_at: When permission was assigned to role (audit trail)
--
-- Constraints:
--   - UNIQUE(role_id, permission_id): Role can't have same permission twice
--   - FOREIGN KEY role_id → roles.id (CASCADE DELETE)
--   - FOREIGN KEY permission_id → permissions.id (CASCADE DELETE)
--
-- Permission Assignment:
--   - Admin assigns permission to role via assign_permission_to_role() function
--   - No assigned_by column (role-level assignment, not user-level)
--
-- Cascading Deletion:
--   - If role deleted → all role_permissions for that role cascade delete
--   - If permission deleted → all role_permissions for that permission cascade delete
--
-- Authorization Flow:
--   1. User makes request (authenticated as user_id)
--   2. RLS policy calls authorize('permission.name')
--   3. authorize() looks up user's roles via user_roles
--   4. authorize() checks if any role has permission via role_permissions
--   5. If found → access granted, else → access denied
--
-- Example Permission Setup:
--   -- Role: editor
--   -- Permissions: posts.read, posts.edit, posts.create
--   INSERT INTO role_permissions (role_id, permission_id)
--   SELECT
--     (SELECT id FROM roles WHERE name = 'editor'),
--     id FROM permissions WHERE name IN ('posts.read', 'posts.edit', 'posts.create');
--
-- Query Patterns:
--   -- Get all permissions for a role
--   SELECT p.name FROM role_permissions rp
--   JOIN permissions p ON p.id = rp.permission_id
--   WHERE rp.role_id = (SELECT id FROM roles WHERE name = 'admin');
--
--   -- Get all roles with a specific permission
--   SELECT r.name FROM role_permissions rp
--   JOIN roles r ON r.id = rp.role_id
--   WHERE rp.permission_id = (SELECT id FROM permissions WHERE name = 'secrets.delete');
--
-- Usage:
--   -- Assign permission to role (via function)
--   SELECT assign_permission_to_role('editor', 'posts.edit');
--
--   -- Remove permission from role (via function)
--   SELECT remove_permission_from_role('editor', 'posts.delete');
CREATE TABLE IF NOT EXISTS "public"."role_permissions" (
    "id" "uuid" DEFAULT "gen_random_uuid"() NOT NULL,
    "role_id" "uuid" NOT NULL,
    "permission_id" "uuid" NOT NULL,
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL
);

ALTER TABLE "public"."role_permissions" OWNER TO "postgres";

COMMENT ON TABLE "public"."role_permissions" IS 'Maps roles to permissions (NEW table-based version). Will replace role_permissions table.';

COMMENT ON COLUMN "public"."role_permissions"."role_id" IS 'Foreign key to roles table (replaces role enum)';

COMMENT ON COLUMN "public"."role_permissions"."permission_id" IS 'Foreign key to permissions table (replaces permission enum)';


-- ============================================================================
-- End of File: rbac_tables.sql
-- ============================================================================
-- Next Migration: 20251023000009_passkey_tables.sql (Passkey Tables)
-- ============================================================================
