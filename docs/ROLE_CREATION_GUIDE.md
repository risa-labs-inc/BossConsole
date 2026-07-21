# Role Creation Guide

This guide explains how to use the Role Creation feature in BOSS to dynamically create new roles and permissions at runtime.

## Overview

The Role Creation feature allows administrators to:
- Create new roles dynamically (extends PostgreSQL `app_role` enum)
- Create new permissions dynamically (extends PostgreSQL `app_permission` enum)
- Assign permissions to roles
- Remove permissions from roles
- View all roles and their assigned permissions

## Access Control

**Admin Only**: This feature is only accessible to users with the `admin` role. The "Admin: Create Roles" panel will only appear in the sidebar for admin users.

## Creating a New Role

1. Open the **Admin: Create Roles** panel from the right sidebar
2. Click the **Create Role** button in the top-right
3. Enter a role name following these rules:
   - Must be lowercase
   - Must start with a letter
   - 3-50 characters long
   - Only alphanumeric characters and underscores
   - Cannot use reserved names: `user`, `admin`, `authenticated`, `anon`, `service_role`, `postgres`
4. Optionally add a description
5. Click **Create**

**Examples of valid role names:**
- `developer`
- `content_editor`
- `super_admin`
- `data_analyst`

**Examples of invalid role names:**
- `Dev` (contains uppercase)
- `2admin` (starts with number)
- `qa` (too short, minimum 3 characters)
- `admin` (reserved name)

## Creating a New Permission

1. Open the **Admin: Create Roles** panel
2. Click the **Create Permission** button in the top-right
3. Enter a permission name following these rules:
   - Must follow `domain.action` format
   - Must be lowercase
   - Both domain and action: 1-30 characters
   - Only alphanumeric characters and underscores
4. Optionally add a description
5. Click **Create**

**Examples of valid permission names:**
- `code.review`
- `content.publish`
- `users.delete`
- `reports.generate`
- `system_settings.modify`

**Examples of invalid permission names:**
- `codeReview` (not in domain.action format)
- `code.Review` (contains uppercase)
- `code` (missing action part)
- `code.review.approve` (too many parts)

## Assigning Permissions to Roles

1. In the **Roles** list (left side), click on a role
2. The role details will appear on the right side
3. Click **Assign Permission** button
4. Search and select a permission from the list
5. Click **Assign**

Only permissions that are not already assigned to the role will be shown.

## Removing Permissions from Roles

1. Click on a role in the **Roles** list
2. In the permissions list, click the **X** button next to the permission you want to remove
3. The permission will be removed from the role

## Technical Details

### Database Implementation

The system uses PostgreSQL ENUMs for type safety:

```sql
-- Roles are stored in the app_role enum
CREATE TYPE public.app_role AS ENUM ('user', 'admin', 'developer', ...);

-- Permissions are stored in the app_permission enum
CREATE TYPE public.app_permission AS ENUM ('users.read', 'code.review', ...);

-- Role-permission mappings
CREATE TABLE public.role_permissions (
    role app_role NOT NULL,
    permission app_permission NOT NULL,
    UNIQUE (role, permission)
);
```

### Dynamic Enum Extension

When you create a new role or permission, the system executes:

```sql
ALTER TYPE public.app_role ADD VALUE 'new_role';
ALTER TYPE public.app_permission ADD VALUE 'new_permission';
```

This is done through secure RPC functions with admin validation.

### Security

All operations are protected by:
1. **Client-side validation**: Input validation before sending to server
2. **Server-side validation**: Regex patterns enforced in database functions
3. **Admin checks**: All RPC functions verify `is_user_admin(auth.uid())`
4. **RLS policies**: Row-level security prevents unauthorized access
5. **Reserved names**: System roles cannot be modified or recreated

### JWT Claims

After roles and permissions are modified, users may need to refresh their session to see updated permissions in JWT claims:

```kotlin
// Claims are automatically refreshed on next authentication
val claims = RoleClaims.fromJWTClaims(session.accessToken.claims)
println("User roles: ${claims.userRoles}")
```

## Best Practices

1. **Use descriptive role names**: `content_editor` is better than `ce`
2. **Follow permission naming convention**: Always use `domain.action` format
3. **Start with minimal permissions**: Add only necessary permissions to roles
4. **Document custom roles**: Keep track of what each custom role is for
5. **Test permission assignments**: Verify users have correct access after assigning roles

## Common Use Cases

### Creating a Developer Role

1. Create role: `developer`
2. Create permissions:
   - `code.read`
   - `code.write`
   - `code.review`
   - `deployments.view`
3. Assign all four permissions to `developer` role
4. Assign `developer` role to users via **Admin: Roles** panel

### Creating a Content Team Structure

1. Create roles:
   - `content_writer`
   - `content_editor`
   - `content_publisher`
2. Create permissions:
   - `content.draft`
   - `content.edit`
   - `content.publish`
   - `content.delete`
3. Assign permissions:
   - `content_writer`: `content.draft`, `content.edit`
   - `content_editor`: `content.draft`, `content.edit`, `content.delete`
   - `content_publisher`: all content permissions

## Troubleshooting

### "Permission denied: Admin role required"
- You must be logged in as an admin user
- Check your roles in **Admin: Roles** panel
- Contact a system administrator if you need admin access

### "Role name is reserved and cannot be used"
- You're trying to create a role with a reserved name
- Choose a different name that doesn't conflict with system roles

### "Invalid role name format"
- Check that your role name follows all validation rules
- Must be lowercase, start with letter, 3-50 chars, alphanumeric + underscore

### "Invalid permission format"
- Permissions must follow `domain.action` format (e.g., `users.read`)
- Both parts must be lowercase, alphanumeric + underscore

## Related Documentation

- [RBAC_GUIDE.md](./RBAC_GUIDE.md) - Complete RBAC system documentation
- Admin Role Management panel - Assign roles to users
- Database migrations - Schema definitions and RPC functions

## API Reference

### RoleCreationService

```kotlin
// Create a new role
suspend fun createRole(roleName: String, description: String?): Result<RoleInfo>

// Create a new permission
suspend fun createPermission(permissionName: String, description: String?): Result<PermissionInfo>

// Assign permission to role
suspend fun assignPermissionToRole(roleName: String, permissionName: String): Result<Unit>

// Remove permission from role
suspend fun removePermissionFromRole(roleName: String, permissionName: String): Result<Unit>

// Get all roles
suspend fun getAllRoles(): Result<List<RoleInfo>>

// Get all permissions
suspend fun getAllPermissions(): Result<List<PermissionInfo>>

// Get role permissions
suspend fun getRolePermissions(roleName: String): Result<RoleWithPermissions>
```

### Validation Functions

```kotlin
// Validate role name (client-side)
fun validateRoleName(roleName: String): String? // Returns error message or null

// Validate permission name (client-side)
fun validatePermissionName(permissionName: String): String? // Returns error message or null
```
