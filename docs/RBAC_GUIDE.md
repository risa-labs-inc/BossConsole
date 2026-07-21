# BOSS RBAC System - Role-Based Access Control

## Overview

BOSS implements a comprehensive Role-Based Access Control (RBAC) system using Supabase's native Custom Claims and Auth Hooks. This system provides secure, scalable role management with minimal overhead and maximum flexibility for plugin extensions.

## Role Hierarchy & Delegated Administration (v3.0)

Roles form a hierarchy (a DAG) where **a parent role inherits the effective permissions of its children**. Inheritance, permission-based plugin gating, and grant delegation are all derived from this single tree.

```
        admin              ← inherits everything below (full access)
       /     \
 boss_admin  finance_admin ← each inherits user
       \     /
        user               ← baseline (legacy plugins assume this)
```

- **Effective permissions** — a user's permissions = the union of permissions on each assigned role plus all of that role's descendants. Computed by `get_role_descendants(role_id)` (recursive, cycle-safe) and `get_effective_permissions(user_id)`. `authorize(permission)` walks this closure, so a permission granted to `user` is automatically held by `boss_admin`, `finance_admin`, and `admin`.
- **JWT claim** — `custom_access_token_hook` injects the effective set as the `user_permissions` claim (for both magic-link and passkey logins). The client parses it into `RoleClaims.permissions` / `UserInfo.permissions` (UI/visibility only — server still enforces).
- **Permission-based plugin gating** — `plugin.json` declares `requiredPermissions: [...]`; the host (`DynamicPluginManager.canAccess`) shows/registers a plugin only if the user's effective permissions contain all of them. An empty list (and legacy plugins) means "any authenticated user". `requiresAdmin` is the legacy gate, still honored. Example: *Admin: Create Roles* requires `["role.read","role.create"]`; *Admin: Roles* requires `["role.read","role.assign"]`.
- **Delegated grants (tree-derived)** — a non-admin grantor may assign only roles **strictly below** its own roles in the tree. `get_grantable_roles()` returns that set (used to populate the assignment dropdown) and `assign_role_to_user` / `remove_role_from_user` enforce it server-side. Full admins may assign any role. Consequence: `boss_admin` cannot assign its sibling `finance_admin` — by construction, with no hand-maintained matrix. To let a role grant more, hang those roles beneath it in `role_hierarchy`.
- **Tables/functions** — new table `role_hierarchy(parent_role_id, child_role_id)`; new permission `role.read`; the role-management RPCs (`create_new_role`, `get_all_roles`, `delete_role`, …) are gated by `authorize('role.<verb>')` instead of a hard `is_user_admin()` check, so domain admins work without holding the literal `admin` role. See migration `20260625000000_role_hierarchy_and_granular_rbac.sql`.

#### Security caveats

- **Admins bypass `authorize()`.** `authorize()` short-circuits to `true` for any user with the `admin` role, so the server agrees with the client (`UserInfo.hasPermission` / `DynamicPluginManager.canAccess` also bypass for admins) even for permissions outside admin's role closure (e.g. a brand-new permission a plugin introduces). Non-admins are evaluated purely against their inherited permission closure.
- **`role.update` delegation is constrained.** `assign_permission_to_role` / `remove_permission_from_role` require `role.update`. Admins may attach any permission to any role. A *non-admin* holder of `role.update` (none today) may only modify roles strictly **below** it (`get_grantable_role_ids`) and may only attach permissions it **itself holds** (`authorize(permission_name)`) — so a delegated role-manager can never mint an admin-level role. This is enforced in the RPCs, not just documented.
- **Listing all users requires `role.read`** (admin + boss_admin), not `user.read`. The baseline `user` role carries `user.read`, so gating the all-users list on it would expose every user to everyone. Plain users still read only their own row.
- **Refresh latency (stale JWT).** Effective permissions are baked into the access token at issuance. *Gaining* and *losing* a role/permission take effect only on the next token refresh (≤ `jwt_expiry`, default 1h) — a revoked permission lingers in the existing token and in the JWT-claim `users` RLS policy until then; force a sign-out / token invalidation for immediate revocation. (A brand-new signup *does* get its baseline `user.*` on the first token, because `handle_new_user` assigns the `user` role before the token is minted.)
- **Helper exposure.** `get_role_descendants`, `get_effective_permissions`, and `get_grantable_role_ids` are `SECURITY DEFINER` internals not granted to `anon`/`authenticated` (only `supabase_auth_admin` for the hook); the only public surface is the self-scoped `get_grantable_roles()`.

#### Plugin API contract change (`AuthDataProvider`)

`AuthDataProvider.hasPermission(name)` / `hasAnyPermission(...)` and the `userPermissions` `StateFlow` now carry **effective permissions** (e.g. `role.create`, `user.read`), not role names. Previously they carried role names (`hasPermission("admin")` style checks). Plugins that passed a *role name* to `hasPermission` or read `userPermissions` expecting role names must migrate to permission names (or use `currentUser`/`isAdmin` for role checks). The in-tree plugins are unaffected (they use `supabaseDataProvider.rpc`); third-party plugins should be advised in release notes.

> **Known limitation — domain-admin role creation needs admin follow-up.** A `boss_admin` (which holds `role.create`) can create a role, but cannot place it in the hierarchy (`role_hierarchy` edits are admin-only), grant it (it is not a descendant), or attach permissions (`role.update` is admin-only). Such a role is inert until an `admin` wires it up.

## Architecture

### Components

1. **Database Layer** - PostgreSQL tables with UUID-based relationships, RLS policies, and functions
2. **Auth Hooks** - JWT claim injection for Supabase native auth (magic link, OAuth)
3. **Edge Functions** - Custom JWT generation for passkey authentication with RBAC claims
4. **Kotlin Services** - Client-side role management and checking
5. **JWT Integration** - Role claims embedded in access tokens (both auth methods)

### Data Flow

```
User Signs Up
    ↓
handle_new_user() trigger → Assigns 'user' role
    ↓
User Authenticates
    ↓
custom_access_token_hook() → Injects role claims into JWT
    ↓
Client receives JWT with claims
    ↓
RoleService parses claims → Available to application
    ↓
RLS policies enforce permissions at database level
```

### Authentication Methods & RBAC

BOSS supports multiple authentication methods, and RBAC role claims are consistently included across all of them:

#### 1. Magic Link Authentication (Supabase Native)
- User requests magic link via email
- Supabase Auth validates the link
- **Auth Hook** (`custom_access_token_hook`) runs before JWT issuance
- Hook fetches user roles from database
- JWT issued with `user_role`, `user_roles`, and `is_admin` claims

#### 2. Passkey Authentication (Custom)
- User authenticates with Touch ID/Windows Hello/Security Key
- Edge Function (`/functions/v1/passkey`) verifies signature
- Edge Function **fetches user roles** from `user_roles` table
- Custom JWT generated with RBAC claims included
- Client imports session with full role information

**Key Point:** Both methods produce identical JWT claim structures, ensuring consistent RBAC behavior regardless of authentication method.

**JWT Claims Structure (Both Methods):**
```json
{
  "sub": "user-uuid",
  "email": "user@example.com",
  "role": "authenticated",
  "user_role": "user",           // Primary role
  "user_roles": ["user", "admin"], // All roles
  "is_admin": true                // Admin flag
}
```

## Database Schema

### Tables

#### `roles`
Defines available roles in the system (fully dynamic).

```sql
CREATE TABLE public.roles (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name TEXT NOT NULL UNIQUE,
    description TEXT,
    is_system BOOLEAN DEFAULT false,
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ DEFAULT now()
);
```

**System Roles:**
- `user` - Default role for all users (is_system=true, cannot be deleted)
- `admin` - Administrative role with full permissions (is_system=true, cannot be deleted)

**Extensibility:** Add custom roles dynamically via RoleCreationService:
```kotlin
RoleCreationService.createRole("ai_trainer", "AI Training Team Member")
```

#### `permissions`
Defines granular permissions (fully dynamic).

```sql
CREATE TABLE public.permissions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name TEXT NOT NULL UNIQUE,
    description TEXT,
    is_system BOOLEAN DEFAULT false,
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ DEFAULT now()
);
```

**System Permissions:**
- `users.read`, `users.write`
- `workspaces.read`, `workspaces.write`, `workspaces.delete`
- `plugins.install`, `plugins.manage`
- `admin.access`

#### `user_roles`
Maps users to their assigned roles (many-to-many relationship).

```sql
CREATE TABLE public.user_roles (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES auth.users(id) ON DELETE CASCADE,
    role_id UUID REFERENCES public.roles(id) ON DELETE CASCADE,
    assigned_by UUID REFERENCES auth.users(id),
    assigned_at TIMESTAMPTZ DEFAULT now(),
    created_at TIMESTAMPTZ DEFAULT now(),
    UNIQUE (user_id, role_id)
);
```

**Fields:**
- `id` - Unique identifier
- `user_id` - Reference to user in auth.users
- `role_id` - Reference to role in roles table (UUID foreign key)
- `assigned_by` - Admin who assigned the role (audit trail)
- `assigned_at` - When the role was assigned (audit trail)

#### `role_permissions`
Maps roles to specific permissions for fine-grained access control.

```sql
CREATE TABLE public.role_permissions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    role_id UUID REFERENCES public.roles(id) ON DELETE CASCADE,
    permission_id UUID REFERENCES public.permissions(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ DEFAULT now(),
    UNIQUE (role_id, permission_id)
);
```

## Kotlin Integration

### Models

#### `RoleClaims` Data Class
```kotlin
data class RoleClaims(
    val userRole: String,          // Primary role name
    val userRoles: List<String>,   // All role names
    val isAdmin: Boolean            // Quick admin check
) {
    fun hasRole(role: String): Boolean
    fun hasAnyRole(vararg roles: String): Boolean
    fun hasAllRoles(vararg roles: String): Boolean
}
```

#### Enhanced `UserInfo`
```kotlin
data class UserInfo(
    val id: String,
    val email: String,
    val createdAt: String,
    val roleClaims: RoleClaims? = null
) {
    val primaryRole: String        // Returns role name (e.g., "admin")
    val roles: List<String>        // Returns list of role names
    val isAdmin: Boolean
    fun hasRole(role: String): Boolean
}
```

#### `UserWithRoles` Data Class
```kotlin
data class UserWithRoles(
    val userId: String,
    val email: String,
    val roles: List<String>,       // Role names
    val isAdmin: Boolean
) {
    val primaryRole: String        // First role or "user"
}
```

### Services

#### `RoleService`
Core service for role management operations.

```kotlin
object RoleService {
    // Parse role claims from JWT session
    fun parseRoleClaimsFromSession(session: UserSession?): RoleClaims?

    // Get user roles
    suspend fun getUserRoles(userId: String): Result<List<UserRole>>

    // Check if user has role
    suspend fun userHasRole(userId: String, roleName: String): Result<Boolean>
    suspend fun isUserAdmin(userId: String): Result<Boolean>

    // Assign/remove roles (admin only)
    suspend fun assignRoleByName(targetUserId: String, roleName: String): Result<Unit>
    suspend fun removeRoleByName(targetUserId: String, roleName: String): Result<Unit>

    // Permission checking
    suspend fun getRolePermissions(roleName: String): Result<List<RolePermission>>
    suspend fun canPerformAction(userId: String, permissionName: String): Result<Boolean>
}
```

#### `RoleCreationService`
Service for creating and managing roles and permissions dynamically.

```kotlin
object RoleCreationService {
    // Role management
    suspend fun createRole(name: String, description: String?): Result<Unit>
    suspend fun getAllRoles(): Result<List<RoleInfo>>
    suspend fun deleteRole(roleName: String): Result<Unit>

    // Permission management
    suspend fun createPermission(name: String, description: String?): Result<Unit>
    suspend fun getAllPermissions(): Result<List<PermissionInfo>>
    suspend fun deletePermission(permissionName: String): Result<Unit>

    // Role-Permission mapping
    suspend fun assignPermissionToRole(roleName: String, permissionName: String): Result<Unit>
    suspend fun removePermissionFromRole(roleName: String, permissionName: String): Result<Unit>
}
```

#### `AuthService` Extensions
```kotlin
object AuthService {
    // Current user role checking
    fun getCurrentUserRoleClaims(): RoleClaims?
    fun isCurrentUserAdmin(): Boolean
    fun currentUserHasRole(roleName: String): Boolean

    // Role management (proxies to RoleService)
    suspend fun assignRoleByName(targetUserId: String, roleName: String): Result<Unit>
    suspend fun removeRoleByName(targetUserId: String, roleName: String): Result<Unit>
    suspend fun getUserRoles(userId: String): Result<List<UserRole>>
    suspend fun userHasPermission(userId: String, permissionName: String): Result<Boolean>
}
```

## Usage Examples

### Check Current User's Role

```kotlin
import ai.rever.boss.services.supabase.AuthService

// Simple admin check
if (AuthService.isCurrentUserAdmin()) {
    println("User is an admin")
}

// Check specific role
if (AuthService.currentUserHasRole("admin")) {
    println("User has admin role")
}

// Check custom role
if (AuthService.currentUserHasRole("ai_trainer")) {
    println("User is an AI trainer")
}

// Get all role claims
val claims = AuthService.getCurrentUserRoleClaims()
claims?.let {
    println("Primary role: ${it.userRole}")      // e.g., "admin"
    println("All roles: ${it.userRoles}")       // e.g., ["user", "admin", "ai_trainer"]
    println("Is admin: ${it.isAdmin}")
}
```

### Observe Current User's Roles

```kotlin
import ai.rever.boss.services.supabase.AuthService
import kotlinx.coroutines.flow.collectLatest

// Observe user changes
AuthService.currentUser.collectLatest { user ->
    user?.let {
        println("User: ${it.email}")
        println("Primary role: ${it.primaryRole}")  // String role name
        println("Is admin: ${it.isAdmin}")

        if (it.hasRole("admin")) {
            // Show admin UI
        }

        if (it.hasRole("ai_trainer")) {
            // Show AI trainer features
        }
    }
}
```

### Assign Role (Admin Only)

```kotlin
import ai.rever.boss.services.supabase.AuthService

// Assign system role
val result = AuthService.assignRoleByName(
    targetUserId = "user-uuid",
    roleName = "admin"
)

result.fold(
    onSuccess = { println("Role assigned successfully") },
    onFailure = { error -> println("Failed to assign role: ${error.message}") }
)

// Assign custom role
val customResult = AuthService.assignRoleByName(
    targetUserId = "user-uuid",
    roleName = "ai_trainer"
)
```

### Remove Role (Admin Only)

```kotlin
val result = AuthService.removeRoleByName(
    targetUserId = "user-uuid",
    roleName = "admin"
)

result.fold(
    onSuccess = { println("Role removed successfully") },
    onFailure = { error -> println("Failed to remove role: ${error.message}") }
)
```

### Create Custom Role (Admin Only)

```kotlin
import ai.rever.boss.services.supabase.RoleCreationService

val result = RoleCreationService.createRole(
    name = "ai_trainer",
    description = "AI Training Team Member"
)

result.fold(
    onSuccess = { println("Role created successfully") },
    onFailure = { error -> println("Failed to create role: ${error.message}") }
)
```

### Check Permissions

```kotlin
import ai.rever.boss.services.supabase.AuthService

val canManagePlugins = AuthService.userHasPermission(
    userId = "user-uuid",
    permissionName = "plugins.manage"
)

canManagePlugins.fold(
    onSuccess = { hasPermission ->
        if (hasPermission) {
            // Allow plugin management
        }
    },
    onFailure = { /* Handle error */ }
)
```

### UI Example: Conditional Rendering

```kotlin
@Composable
fun AdminPanel() {
    val currentUser by AuthService.currentUser.collectAsState()

    if (currentUser?.isAdmin == true) {
        Column {
            Text("Admin Panel")
            Button(onClick = { /* Admin action */ }) {
                Text("Manage Users")
            }
        }
    } else {
        Text("Access Denied")
    }
}
```

## Database Functions

### Admin Functions

#### `assign_role_to_user(target_user_id, target_role)`
Assigns a role to a user by role name. Only callable by admins.

```sql
SELECT public.assign_role_to_user(
    'user-uuid'::uuid,
    'admin'::text
);
```

#### `remove_role_from_user(target_user_id, target_role)`
Removes a role from a user by role name. Only callable by admins. Cannot remove own admin role.

```sql
SELECT public.remove_role_from_user(
    'user-uuid'::uuid,
    'admin'::text
);
```

### Query Functions

#### `check_user_has_role(target_user_id, role_name)`
Check if a user has a specific role by role name.

```sql
SELECT public.check_user_has_role(
    'user-uuid'::uuid,
    'admin'::text
);
```

#### `is_user_admin(check_user_id)`
Quick check if a user is an admin.

```sql
SELECT public.is_user_admin('user-uuid'::uuid);
```

#### `get_user_roles(check_user_id)`
Get all roles for a user.

```sql
SELECT * FROM public.get_user_roles('user-uuid'::uuid);
```

#### `authorize(requested_permission)`
Check if current user (from JWT) has a permission. Used in RLS policies.

```sql
-- In RLS policy
CREATE POLICY "Admins can delete" ON workspaces
    FOR DELETE
    USING (public.authorize('workspaces.delete'));
```

## Row Level Security (RLS)

### Policy Examples

#### User Roles Table

**Users can view their own roles:**
```sql
CREATE POLICY "Users can view their own roles"
    ON public.user_roles
    FOR SELECT
    USING (auth.uid() = user_id);
```

**Admins can view all roles:**
```sql
CREATE POLICY "Admins can view all roles"
    ON public.user_roles
    FOR SELECT
    USING (
        EXISTS (
            SELECT 1 FROM public.user_roles
            WHERE user_id = auth.uid() AND role = 'admin'
        )
    );
```

**Only admins can assign roles:**
```sql
CREATE POLICY "Admins can assign roles"
    ON public.user_roles
    FOR INSERT
    WITH CHECK (
        EXISTS (
            SELECT 1 FROM public.user_roles
            WHERE user_id = auth.uid() AND role = 'admin'
        )
    );
```

### Using Permissions in Custom Policies

```sql
-- Example: Only users with 'workspaces.delete' permission can delete
CREATE POLICY "Authorized users can delete workspaces"
    ON public.workspaces
    FOR DELETE
    USING (public.authorize('workspaces.delete'));
```

## Setup Instructions

### 1. Deploy Migrations

```bash
# Navigate to project root
cd /path/to/boss-main

# Deploy migrations using Supabase CLI
supabase db push

# Or apply migrations manually in Supabase Dashboard
# SQL Editor → New Query → Paste migration content → Run
```

### 2. Enable Auth Hook

1. Go to Supabase Dashboard
2. Navigate to **Authentication → Hooks**
3. Enable **Custom Access Token Hook**
4. Select: `public.custom_access_token_hook`
5. Save changes

### 3. Create First Admin (Manual)

Since the first admin must be created manually (chicken-and-egg problem):

```sql
-- Find your user ID
SELECT id, email FROM auth.users WHERE email = 'your-email@example.com';

-- Assign admin role
INSERT INTO public.user_roles (user_id, role)
VALUES ('your-user-uuid', 'admin');
```

After this, the admin can assign roles to other users via the application.

### 4. Test the System

```kotlin
// In your app initialization
suspend fun testRBAC() {
    // Sign in
    AuthService.sendMagicLink("test@example.com")

    // After authentication
    val claims = AuthService.getCurrentUserRoleClaims()
    println("Role: ${claims?.userRole}") // Should print: Role: user or Role: admin

    // For admins
    if (AuthService.isCurrentUserAdmin()) {
        val result = AuthService.assignRoleByName("other-user-id", "admin")
        println("Assign result: $result")
    }
}
```

## Plugin Integration

### Adding Plugin-Specific Roles (Dynamically)

Plugins can create roles at runtime without database migrations or code changes:

1. **Create plugin-specific role**:
```kotlin
import ai.rever.boss.services.supabase.RoleCreationService

// Create roles for analytics plugin
RoleCreationService.createRole(
    name = "analytics_viewer",
    description = "Can view analytics dashboards"
)

RoleCreationService.createRole(
    name = "analytics_manager",
    description = "Can view and manage analytics"
)
```

2. **Add plugin permissions**:
```kotlin
// Create permissions
RoleCreationService.createPermission(
    name = "plugin.analytics.view",
    description = "View analytics data"
)

RoleCreationService.createPermission(
    name = "plugin.analytics.manage",
    description = "Manage analytics settings"
)

// Map permissions to roles
RoleCreationService.assignPermissionToRole(
    roleName = "analytics_viewer",
    permissionName = "plugin.analytics.view"
)

RoleCreationService.assignPermissionToRole(
    roleName = "analytics_manager",
    permissionName = "plugin.analytics.view"
)

RoleCreationService.assignPermissionToRole(
    roleName = "analytics_manager",
    permissionName = "plugin.analytics.manage"
)
```

3. **Use in plugin code**:
```kotlin
// Check if user can access plugin
if (AuthService.currentUserHasRole("analytics_viewer")) {
    // Show analytics dashboard
}

// Or check permission
val canManage = AuthService.userHasPermission(
    userId = currentUserId,
    permissionName = "plugin.analytics.manage"
)

canManage.fold(
    onSuccess = { hasPermission ->
        if (hasPermission) {
            // Show management UI
        }
    },
    onFailure = { /* Handle error */ }
)
```

### Role Management UI

The Admin Role Management plugin provides a UI for:
- Viewing all users with their roles
- Assigning/removing roles (including custom roles)
- Creating new roles and permissions
- Managing role-permission mappings

All role management operations are available at runtime without code changes.

## Security Considerations

### ⚠️ CRITICAL: Client-Side JWT Parsing Security Model

**The client does NOT verify JWT signatures.** This is intentional and follows industry best practices:

#### Why No Client-Side Signature Verification?

1. **JWT Already Verified Server-Side**: Supabase Auth verifies signatures when issuing tokens
2. **Client Cannot Be Trusted**: Any code running on the client can be modified/debugged
3. **Performance**: Signature verification is expensive and unnecessary on client
4. **Security Happens Server-Side**: RLS policies enforce all authorization

#### What Client-Side Role Checks Are For

✅ **Safe Uses:**
- Showing/hiding UI elements (buttons, menu items)
- Displaying role badges and user info
- Optimistic UI updates
- Reducing unnecessary API calls

❌ **NEVER Use For:**
- Granting access to sensitive data
- Bypassing server-side authorization
- Making security decisions
- Skipping database permission checks

#### Security Architecture

```
Client Side                          Server Side
┌──────────────────┐                ┌──────────────────────┐
│ Parse JWT Claims │                │ Verify JWT Signature │
│ (informational)  │   →  API  →    │ Check RLS Policies   │
│                  │      Call       │ Execute if Authorized│
└──────────────────┘                └──────────────────────┘
     UI Only                         Actual Security
```

**Example: Admin Role Check**

```kotlin
// Client side (UI convenience)
if (AuthService.isCurrentUserAdmin()) {
    // ✅ Show "Delete User" button
    // ❌ DO NOT skip server call
}

// Server side (actual security)
// RLS Policy on user_roles table:
CREATE POLICY "Only admins can delete users" ON auth.users
  FOR DELETE USING (
    public.is_user_admin(auth.uid())  -- Verifies JWT claims server-side
  );
```

**Key Principle:** Client-side checks are **optimistic assumptions**. Server-side checks are **enforced guarantees**.

### 1. Admin Protection
- Users cannot remove their own admin role (prevents lockout)
- First admin must be created manually via SQL
- All role changes are audited (assigned_by, assigned_at)

### 2. JWT Security
- **Role claims are signed** in JWT by Supabase (server-side)
- **Signature verification** happens server-side on every API request
- **Client parsing is informational only** - does NOT verify signature
- Claims refresh automatically on token renewal (every hour)
- Forged JWTs are rejected by Supabase API (signature mismatch)
- Server-side RLS policies check `auth.jwt()` claims (verified)

### 3. RLS Policies
- All tables with roles/permissions have RLS enabled
- Service role bypasses RLS (for Edge Functions only)
- Users can only see their own roles
- Admins can view/modify all roles (verified server-side)
- RLS policies use `auth.jwt()` which contains verified claims

### 4. Permission Checking
- Always use `authorize()` function in RLS policies
- **Never trust client-side role checks alone**
- Database enforces permissions regardless of client claims
- All mutations protected by RLS + database functions
- Edge Functions use service role to bypass RLS (trusted environment)

## Troubleshooting

### Roles not appearing in JWT

**For Magic Link Authentication:**

1. **Check auth hook is enabled:**
   - Dashboard → Authentication → Hooks → Custom Access Token Hook

2. **Verify role assignment:**
   ```sql
   SELECT * FROM public.user_roles WHERE user_id = 'your-user-uuid';
   ```

3. **Test hook function directly:**
   ```sql
   SELECT public.custom_access_token_hook(
       jsonb_build_object(
           'user_id', 'your-user-uuid',
           'claims', '{}'::jsonb
       )
   );
   ```

**For Passkey Authentication:**

1. **Verify Edge Function is deployed:**
   ```bash
   supabase functions deploy passkey --project-ref YOUR_PROJECT_REF
   ```

2. **Check Edge Function has JWT_SECRET configured:**
   - Dashboard → Edge Functions → passkey → Settings
   - Ensure `JWT_SECRET` environment variable is set

3. **Verify role assignment:**
   ```sql
   SELECT * FROM public.user_roles WHERE user_id = 'your-user-uuid';
   ```

**For Both Methods:**

4. **Re-authenticate:** Log out and log back in to get a fresh JWT with updated claims.

5. **Check JWT payload in logs:** Look for debug output showing parsed claims:
   ```
   🔍 [RBAC DEBUG] JWT Claims parsed:
     user_role: user
     user_roles: [user, admin]
     is_admin: true
   ```

### "Only admins can assign roles" error

- Ensure the user making the call is an admin
- Check: `SELECT * FROM public.user_roles WHERE user_id = auth.uid() AND role = 'admin';`
- First admin must be created manually via SQL

### RLS policies blocking access

- Service role bypasses RLS - use for admin operations
- Check if user has required role: `SELECT public.user_has_role(auth.uid(), 'admin');`
- Verify RLS policies allow the operation

### JWT decode errors in Kotlin

- Ensure `java.util.Base64` is available (should be standard in JVM)
- Check JWT format (should be three dot-separated parts)
- Verify access token is not expired

## Performance Considerations

### Caching
- Role claims are cached in JWT (no database lookup on every request)
- JWT refresh updates role claims automatically
- RLS policies use efficient indexes on user_id and role columns

### Indexes
All tables have appropriate indexes:
```sql
CREATE INDEX idx_user_roles_user_id ON public.user_roles(user_id);
CREATE INDEX idx_user_roles_role ON public.user_roles(role);
CREATE INDEX idx_role_permissions_role ON public.role_permissions(role);
```

### Query Optimization
- Use `user_has_role()` function for simple checks
- Use `authorize()` in RLS policies (efficient with proper indexes)
- Batch role checks when possible

## Future Enhancements

### Potential Additions
1. **Role Hierarchy** - Roles that inherit permissions from other roles
2. **Temporary Roles** - Time-limited role assignments
3. **Role Groups** - Collections of roles for easy assignment
4. **Audit Log UI** - View who assigned what role and when
5. **Permission Builder** - UI for creating custom permissions
6. **Multi-Tenant Roles** - Roles scoped to specific workspaces/organizations

## References

- [Supabase RBAC Documentation](https://supabase.com/docs/guides/database/postgres/custom-claims-and-role-based-access-control-rbac)
- [PostgreSQL Row Level Security](https://www.postgresql.org/docs/current/ddl-rowsecurity.html)
- [JWT Claims](https://datatracker.ietf.org/doc/html/rfc7519#section-4)
- [Issue #54](https://github.com/risa-labs-inc/BOSS-Kotlin/issues/54) - Original requirement

## Support

For questions or issues with the RBAC system:
1. Check this documentation
2. Review the code comments in migration files
3. Open an issue on GitHub
4. Contact the development team

---

**Version:** 2.0.0
**Last Updated:** 2025-10-20
**Author:** BOSS Development Team

**Changelog:**
- v2.0.0 (2025-10-20): **BREAKING CHANGE** - Removed ENUM-based roles/permissions, migrated to fully table-based dynamic RBAC system
- v1.2.0 (2025-10-18): Added critical security model documentation (JWT signature verification)
- v1.1.0 (2025-10-18): Added passkey authentication RBAC integration documentation
- v1.0.0 (2025-01-18): Initial RBAC system release
