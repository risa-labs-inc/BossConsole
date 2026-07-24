package ai.rever.boss.services.supabase

import ai.rever.boss.services.supabase.models.*
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.rpc
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Service for managing user queries and user-related operations
 *
 * This service provides methods to fetch user data from the public.users table
 * and combines it with role information from RoleService.
 *
 * Security Model:
 * - All queries are protected by Row Level Security (RLS)
 * - Admin-only operations will fail for non-admin users
 * - RLS policies enforce server-side authorization
 *
 * Features:
 * - Fetch all users (admin only)
 * - Fetch users with their roles combined
 * - Query individual user details
 */
object UserService {
    private val logger = BossLogger.forComponent("UserService")

    /**
     * Get the Supabase client
     */
    private val client
        get() = SupabaseConfig.client

    /**
     * Fetch all users from the public.users table with pagination
     *
     * This operation requires admin access - RLS will enforce this.
     * Non-admin users will receive an empty list or error.
     *
     * @param limit Maximum number of users to fetch (default: 50)
     * @param offset Number of users to skip for pagination (default: 0)
     * @return Result containing list of users and whether there are more users to load
     */
    suspend fun getAllUsers(
        limit: Int = 50,
        offset: Int = 0,
    ): Result<PaginatedResult<UserBasicInfo>> =
        try {
            // Fetch limit + 1 to check if there are more results
            val users =
                client
                    .from("users")
                    .select(Columns.ALL) {
                        range(offset.toLong(), (offset + limit).toLong())
                    }.decodeList<UserBasicInfo>()

            // Check if there are more results
            val hasMore = users.size > limit
            val actualUsers = if (hasMore) users.take(limit) else users

            logger.debug(LogCategory.AUTH, "Fetched users", mapOf("count" to actualUsers.size, "offset" to offset, "hasMore" to hasMore))
            Result.success(PaginatedResult(actualUsers, hasMore))
        } catch (e: Exception) {
            logger.error(LogCategory.AUTH, "Failed to fetch users", error = e)
            Result.failure(Exception("Failed to fetch users: ${e.message}"))
        }

    /**
     * Fetch all users with their roles combined (paginated)
     *
     * This method:
     * 1. Fetches users from public.users with pagination (admin only)
     * 2. For each user, fetches their roles using RoleService
     * 3. Combines the data into UserWithRoles objects
     *
     * This is the primary method for the Admin Role Management UI.
     *
     * @param limit Maximum number of users to fetch (default: 50)
     * @param offset Number of users to skip for pagination (default: 0)
     * @return Result containing list of users with roles and whether there are more to load
     */
    suspend fun getAllUsersWithRoles(
        limit: Int = 50,
        offset: Int = 0,
    ): Result<PaginatedResult<UserWithRoles>> {
        return try {
            // Step 1: Fetch users with pagination
            val usersResult = getAllUsers(limit, offset)
            if (usersResult.isFailure) {
                return Result.failure(usersResult.exceptionOrNull() ?: Exception("Unknown error fetching users"))
            }

            val paginatedUsers = usersResult.getOrThrow()
            val users = paginatedUsers.data
            logger.debug(LogCategory.AUTH, "Fetching roles for users", mapOf("count" to users.size, "offset" to offset))

            // Step 2: Fetch roles for each user
            val usersWithRoles =
                users.map { user ->
                    val rolesResult = RoleService.getUserRoles(user.id)
                    val userRoles = rolesResult.getOrNull() ?: emptyList()

                    // Get role names as strings (supports dynamic roles)
                    val roleNames = userRoles.map { it.role }
                    val isAdmin = roleNames.contains("admin")

                    UserWithRoles(
                        userId = user.id,
                        email = user.email,
                        roles = roleNames,
                        isAdmin = isAdmin,
                    )
                }

            logger.debug(
                LogCategory.AUTH,
                "Successfully fetched users with roles",
                mapOf(
                    "count" to usersWithRoles.size,
                    "hasMore" to paginatedUsers.hasMore,
                ),
            )
            Result.success(PaginatedResult(usersWithRoles, paginatedUsers.hasMore))
        } catch (e: Exception) {
            logger.error(LogCategory.AUTH, "Failed to fetch users with roles", error = e)
            Result.failure(Exception("Failed to fetch users with roles: ${e.message}"))
        }
    }

    /**
     * Search users by email with pagination (server-side)
     *
     * Uses PostgreSQL ILIKE for case-insensitive search across ALL users in database.
     * Much more efficient and accurate than client-side filtering.
     *
     * @param searchQuery Email search query (case-insensitive)
     * @param limit Maximum number of users to fetch (default: 50)
     * @param offset Number of users to skip for pagination (default: 0)
     * @return Result containing list of matching users with roles and whether there are more
     */
    suspend fun searchUsersByEmail(
        searchQuery: String,
        limit: Int = 50,
        offset: Int = 0,
    ): Result<PaginatedResult<UserWithRoles>> {
        return try {
            // If search query is blank, return all users
            if (searchQuery.isBlank()) {
                return getAllUsersWithRoles(limit, offset)
            }

            // Step 1: Search users in database with ILIKE (case-insensitive)
            val users =
                client
                    .from("users")
                    .select(Columns.ALL) {
                        filter {
                            ilike("email", "%$searchQuery%")
                        }
                        range(offset.toLong(), (offset + limit).toLong())
                    }.decodeList<UserBasicInfo>()

            // Check if there are more results
            val hasMore = users.size > limit
            val actualUsers = if (hasMore) users.take(limit) else users

            logger.debug(
                LogCategory.AUTH,
                "Search found users",
                mapOf(
                    "count" to actualUsers.size,
                    "query" to searchQuery,
                    "offset" to offset,
                    "hasMore" to hasMore,
                ),
            )

            // Step 2: Fetch roles for each user
            val usersWithRoles =
                actualUsers.map { user ->
                    val rolesResult = RoleService.getUserRoles(user.id)
                    val userRoles = rolesResult.getOrNull() ?: emptyList()

                    // Get role names as strings (supports dynamic roles)
                    val roleNames = userRoles.map { it.role }
                    val isAdmin = roleNames.contains("admin")

                    UserWithRoles(
                        userId = user.id,
                        email = user.email,
                        roles = roleNames,
                        isAdmin = isAdmin,
                    )
                }

            logger.debug(
                LogCategory.AUTH,
                "Successfully fetched users with roles for search",
                mapOf(
                    "count" to usersWithRoles.size,
                    "query" to searchQuery,
                ),
            )
            Result.success(PaginatedResult(usersWithRoles, hasMore))
        } catch (e: Exception) {
            logger.error(LogCategory.AUTH, "Failed to search users", error = e)
            Result.failure(Exception("Failed to search users: ${e.message}"))
        }
    }

    /**
     * Fetch a single user by their ID
     */
    suspend fun getUserById(userId: String): Result<UserBasicInfo> =
        try {
            val user =
                client
                    .from("users")
                    .select(Columns.ALL) {
                        filter {
                            eq("id", userId)
                        }
                    }.decodeSingle<UserBasicInfo>()

            Result.success(user)
        } catch (e: Exception) {
            logger.error(LogCategory.AUTH, "Failed to fetch user", mapOf("userId" to userId, "error" to (e.message ?: "unknown")))
            Result.failure(Exception("Failed to fetch user: ${e.message}"))
        }

    /**
     * Fetch a single user with their roles
     */
    suspend fun getUserWithRoles(userId: String): Result<UserWithRoles> {
        return try {
            val userResult = getUserById(userId)
            if (userResult.isFailure) {
                return Result.failure(userResult.exceptionOrNull() ?: Exception("Unknown error fetching user"))
            }

            val user = userResult.getOrThrow()
            val rolesResult = RoleService.getUserRoles(userId)
            val userRoles = rolesResult.getOrNull() ?: emptyList()

            // Get role names as strings (supports dynamic roles)
            val roleNames = userRoles.map { it.role }
            val isAdmin = roleNames.contains("admin")

            val userWithRoles =
                UserWithRoles(
                    userId = user.id,
                    email = user.email,
                    roles = roleNames,
                    isAdmin = isAdmin,
                )

            Result.success(userWithRoles)
        } catch (e: Exception) {
            logger.error(LogCategory.AUTH, "Failed to fetch user with roles", error = e)
            Result.failure(Exception("Failed to fetch user with roles: ${e.message}"))
        }
    }

    /**
     * Delete a user account (admin only)
     *
     * This operation:
     * - Requires admin access (enforced by database function)
     * - Deletes user's role assignments
     * - Deletes user record from public.users table
     * - Cannot delete yourself
     * - Cannot delete other admin users (remove admin role first)
     *
     * @param userId The ID of the user to delete
     * @return Result indicating success or failure
     */
    suspend fun deleteUser(userId: String): Result<Unit> =
        try {
            logger.info(LogCategory.AUTH, "Attempting to delete user", mapOf("userId" to userId))

            // Call the PostgreSQL delete_user function via RPC
            client.postgrest.rpc(
                function = "delete_user",
                parameters =
                    kotlinx.serialization.json.buildJsonObject {
                        put("target_user_id", userId)
                    },
            )

            logger.info(LogCategory.AUTH, "Successfully deleted user", mapOf("userId" to userId))
            Result.success(Unit)
        } catch (e: Exception) {
            logger.error(LogCategory.AUTH, "Failed to delete user", error = e)
            Result.failure(Exception("Failed to delete user: ${e.message}"))
        }
}

/**
 * Basic user information from the public.users table
 */
@Serializable
data class UserBasicInfo(
    val id: String,
    val email: String,
    val created_at: String? = null,
    val updated_at: String? = null,
)

/**
 * Paginated result wrapper
 *
 * @param data The list of items in this page
 * @param hasMore Whether there are more items to load
 */
data class PaginatedResult<T>(
    val data: List<T>,
    val hasMore: Boolean,
)
