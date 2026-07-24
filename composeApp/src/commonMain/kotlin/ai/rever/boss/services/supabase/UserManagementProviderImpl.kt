package ai.rever.boss.services.supabase

import ai.rever.boss.plugin.api.PaginatedUsersData
import ai.rever.boss.plugin.api.RoleInfoData
import ai.rever.boss.plugin.api.UserManagementProvider
import ai.rever.boss.plugin.api.UserWithRolesData

/**
 * Implementation of UserManagementProvider that wraps UserService and RoleCreationService.
 *
 * This adapter allows auth-dependent panels to be extracted to separate
 * plugin modules while keeping the actual Supabase services in composeApp.
 */
class UserManagementProviderImpl : UserManagementProvider {
    override suspend fun getAllUsersWithRoles(
        limit: Int,
        offset: Int,
    ): Result<PaginatedUsersData> =
        UserService.getAllUsersWithRoles(limit, offset).map { paginated ->
            PaginatedUsersData(
                data =
                    paginated.data.map { user ->
                        UserWithRolesData(
                            id = user.userId,
                            email = user.email,
                            displayName = null,
                            roles = user.roles,
                            createdAt = 0L, // UserWithRoles has string date
                            lastSignIn = null,
                        )
                    },
                hasMore = paginated.hasMore,
            )
        }

    override suspend fun searchUsersByEmail(
        query: String,
        limit: Int,
        offset: Int,
    ): Result<PaginatedUsersData> =
        UserService.searchUsersByEmail(query, limit, offset).map { paginated ->
            PaginatedUsersData(
                data =
                    paginated.data.map { user ->
                        UserWithRolesData(
                            id = user.userId,
                            email = user.email,
                            displayName = null,
                            roles = user.roles,
                            createdAt = 0L,
                            lastSignIn = null,
                        )
                    },
                hasMore = paginated.hasMore,
            )
        }

    override suspend fun assignRole(
        userId: String,
        roleName: String,
    ): Result<Unit> = RoleService.assignRoleByName(userId, roleName)

    override suspend fun removeRole(
        userId: String,
        roleName: String,
    ): Result<Unit> = RoleService.removeRoleByName(userId, roleName)

    override suspend fun deleteUser(userId: String): Result<Unit> = UserService.deleteUser(userId)

    override suspend fun getAllRoles(): Result<List<RoleInfoData>> =
        RoleCreationService.getAllRoles().map { roles ->
            roles.map { role ->
                RoleInfoData(
                    id = role.id ?: "",
                    name = role.name,
                    description = role.description,
                    permissions = emptyList(), // Permissions are fetched separately
                    createdAt = 0L,
                )
            }
        }
}
