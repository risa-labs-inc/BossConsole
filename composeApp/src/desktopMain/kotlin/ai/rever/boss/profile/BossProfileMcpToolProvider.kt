package ai.rever.boss.profile

import ai.rever.boss.components.workspaces.LayoutWorkspace
import ai.rever.boss.components.workspaces.isSpaceSlot
import ai.rever.boss.components.workspaces.workspaceManager
import ai.rever.boss.mcp.McpToolRegistryImpl
import ai.rever.boss.mcp.isSafeWorkspaceId
import ai.rever.boss.plugin.api.McpToolArgs
import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import ai.rever.boss.plugin.pathutils.BossDirectories
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The API surface of BOSS profiles: separate BOSS accounts on one machine, each in its own
 * process and window, with its own sign-in, browser profile, plugins, plugin data and Spaces.
 *
 * Hidden by design. Nothing in the UI creates or opens a profile, and a Space opened the ordinary
 * way still opens in the current window under the current profile; only these tools (and
 * [BossProfileLauncher], which they drive) reach the feature. `profile_create` and `profile_open`
 * are `readOnly = false`, so under the factory policy every call is held for the operator in the
 * MCP approval dialog - the confirmation a new window, process or sign-in warrants.
 */
// One cohesive MCP tool provider; handlers stay beside their tool definitions.
@Suppress("TooManyFunctions")
object BossProfileMcpToolProvider : McpToolProvider {
    override val providerId: String = "boss-profiles"

    fun register() {
        McpToolRegistryImpl.registerProvider(this)
    }

    override fun tools(): List<McpToolDefinition> =
        listOf(
            McpToolDefinition(
                name = "profile_list",
                description = LIST_DESCRIPTION,
                inputSchema = """{"type":"object","properties":{}}""",
                readOnly = true,
                handler = McpToolHandler { list() },
            ),
            McpToolDefinition(
                name = "profile_create",
                description = CREATE_DESCRIPTION,
                inputSchema = CREATE_SCHEMA,
                readOnly = false,
                handler = McpToolHandler { args -> create(args) },
            ),
            McpToolDefinition(
                name = "profile_open",
                description = OPEN_DESCRIPTION,
                inputSchema = OPEN_SCHEMA,
                readOnly = false,
                handler = McpToolHandler { args -> open(args) },
            ),
        )

    private suspend fun list(): McpToolResult {
        val profiles =
            withContext(Dispatchers.IO) {
                BossProfileStore.list().map { it to BossProfileLauncher.isOpen(it) }
            }
        return McpToolResult(
            buildJsonObject {
                put("currentProfileId", BossDirectories.profileId ?: "main")
                put(
                    "profiles",
                    buildJsonArray { profiles.forEach { (profile, running) -> add(describe(profile, running)) } },
                )
            }.toString(),
        )
    }

    private suspend fun create(args: McpToolArgs): McpToolResult {
        val name = args.string("name")?.takeIf { it.isNotBlank() }
        val auth = authOf(args)
        val plugins = pluginsOf(args)
        if (name == null || auth == null || plugins == null) {
            return error(
                when {
                    name == null -> "'name' is required"
                    auth == null -> "'auth' must be 'shared' or 'separate'"
                    else -> "'plugins' must be 'copy' or 'empty'"
                },
            )
        }
        val id = args.string("profileId")?.takeIf { it.isNotBlank() }
        return withContext(Dispatchers.IO) { BossProfileStore.create(name, auth, plugins, id) }.fold(
            onSuccess = { McpToolResult(describe(it, running = false).toString()) },
            onFailure = { error(it.message ?: "Could not create the profile") },
        )
    }

    private suspend fun open(args: McpToolArgs): McpToolResult {
        val target =
            when (val resolved = resolveOpenTarget(args)) {
                is OpenTarget.Refused -> return error(resolved.message)
                is OpenTarget.Ready -> resolved
            }
        return BossProfileLauncher
            .open(target.profile, target.workspace)
            .fold(
                onSuccess = { McpToolResult(openResult(it, target).toString()) },
                onFailure = { error(it.message ?: "Could not open the profile") },
            )
    }

    private sealed class OpenTarget {
        data class Ready(
            val profile: BossProfile,
            val workspace: LayoutWorkspace?,
        ) : OpenTarget()

        data class Refused(
            val message: String,
        ) : OpenTarget()
    }

    /** Which profile to open and which Space to show there, or why the request is refused. */
    @Suppress("ReturnCount") // one early exit per refusal reads clearer than a nested chain
    private suspend fun resolveOpenTarget(args: McpToolArgs): OpenTarget {
        val workspaceId = args.string("workspaceId")?.takeIf { it.isNotBlank() }
        if (workspaceId != null && !isSafeWorkspaceId(workspaceId)) {
            return OpenTarget.Refused("Invalid workspaceId: expected an identifier, not a path")
        }
        val workspace = workspaceId?.let(::resolveWorkspace)
        if (workspaceId != null && workspace == null) {
            return OpenTarget.Refused("No saved Space '$workspaceId' in this window's profile")
        }
        val profileId = args.string("profileId")?.takeIf { it.isNotBlank() }
        val newName = args.string("newProfileName")?.takeIf { it.isNotBlank() }
        if (profileId != null && newName != null) {
            return OpenTarget.Refused("Give 'profileId' or 'newProfileName', not both")
        }
        val profile =
            withContext(Dispatchers.IO) {
                when {
                    profileId != null -> BossProfileStore.get(profileId)
                    newName != null -> createForOpen(newName, args)
                    workspaceId != null -> BossProfileStore.profileForWorkspace(workspaceId)
                    else -> null
                }
            }
        return if (profile != null) {
            OpenTarget.Ready(profile, workspace)
        } else {
            OpenTarget.Refused(noProfileMessage(profileId, newName, workspaceId))
        }
    }

    private fun noProfileMessage(
        profileId: String?,
        newName: String?,
        workspaceId: String?,
    ): String =
        when {
            profileId != null -> "No BOSS profile '$profileId'"
            newName != null -> "Could not create the profile (check 'auth' and 'plugins')"
            workspaceId != null -> "Space '$workspaceId' is not bound to a profile; give 'profileId' or a new name"
            else -> "Give 'profileId', 'newProfileName' or 'workspaceId'"
        }

    private fun createForOpen(
        name: String,
        args: McpToolArgs,
    ): BossProfile? {
        val auth = authOf(args)
        val plugins = pluginsOf(args)
        return if (auth != null && plugins != null) BossProfileStore.create(name, auth, plugins).getOrNull() else null
    }

    private fun openResult(
        outcome: BossProfileLauncher.Outcome,
        target: OpenTarget.Ready,
    ): JsonObject =
        buildJsonObject {
            put("success", true)
            val running =
                when (outcome) {
                    is BossProfileLauncher.Outcome.WindowOpened -> {
                        put("status", "window_opened")
                        outcome.windowId?.let { put("windowId", it) }
                        true
                    }

                    is BossProfileLauncher.Outcome.Launched -> {
                        // Started, not yet answering: profile_list reports when it is up.
                        put("status", "starting")
                        false
                    }

                    is BossProfileLauncher.Outcome.Forwarded -> {
                        // Without a Space there is nothing to hand over; the profile's own window
                        // is left as it is (bringing another process to the front is not done).
                        put("status", if (outcome.workspaceFile != null) "forwarded" else "already_running")
                        true
                    }
                }
            put("profile", describe(target.profile, running))
            target.workspace?.let { put("workspaceId", it.id) }
        }

    /** A saved Space of THIS process's profile; shipped templates and the session slot are not Spaces. */
    private fun resolveWorkspace(id: String): LayoutWorkspace? =
        if (isSpaceSlot(id)) null else workspaceManager.workspaces.value.firstOrNull { it.id == id }

    private fun authOf(args: McpToolArgs): ProfileAuthMode? =
        when (args.string("auth")?.lowercase()) {
            null, "", "separate" -> ProfileAuthMode.SEPARATE
            "shared" -> ProfileAuthMode.SHARED
            else -> null
        }

    private fun pluginsOf(args: McpToolArgs): ProfilePluginSeed? =
        when (args.string("plugins")?.lowercase()) {
            null, "", "copy" -> ProfilePluginSeed.COPY_MAIN
            "empty" -> ProfilePluginSeed.EMPTY
            else -> null
        }

    private fun describe(
        profile: BossProfile,
        running: Boolean,
    ): JsonObject =
        buildJsonObject {
            put("id", profile.id)
            put("name", profile.name)
            put("auth", profile.auth.name.lowercase())
            put("running", running)
            put("workspaceIds", buildJsonArray { profile.workspaceIds.forEach { add(JsonPrimitive(it)) } })
        }

    private fun error(message: String) = McpToolResult(message, isError = true)

    private val LIST_DESCRIPTION =
        listOf(
            "List BOSS profiles. A 'shared' profile is the main BOSS account in windows of its own",
            "with a separate browser profile; a 'separate' profile is another sign-in in its own",
            "BOSS process, with its own plugins and plugin data. Reports which are open, which",
            "Spaces are bound to each, and which profile this window's process runs as.",
        ).joinToString(" ")

    private val CREATE_DESCRIPTION =
        listOf(
            "Create a BOSS profile without opening it. 'auth' is 'shared' (the main BOSS account:",
            "its windows share this sign-in, plugins and plugin settings, with their own browser",
            "profile) or 'separate' (its own BOSS process and sign-in, possibly another account).",
            "'plugins' applies to separate profiles: 'copy' (the main plugins, without their",
            "settings) or 'empty'.",
        ).joinToString(" ")

    private val OPEN_DESCRIPTION =
        listOf(
            "Open a BOSS profile in its own window, optionally showing a saved Space there. Give",
            "'profileId', or 'newProfileName' to create one first, or only 'workspaceId' to reopen",
            "the profile that Space is bound to. A shared profile opens a new window in this BOSS",
            "process; a separate one opens its own BOSS process, which receives the Space (copied on",
            "first open) in its existing window if it is already running.",
        ).joinToString(" ")

    private const val AUTH_PROPERTY =
        """"auth":{"type":"string","enum":["shared","separate"],"description":"Default: separate"}"""
    private const val PLUGINS_PROPERTY =
        """"plugins":{"type":"string","enum":["copy","empty"],"description":"Default: copy"}"""

    private const val CREATE_SCHEMA =
        """{"type":"object","required":["name"],"properties":{""" +
            """"name":{"type":"string","description":"Display name"},""" +
            AUTH_PROPERTY + "," + PLUGINS_PROPERTY + "," +
            """"profileId":{"type":"string",""" +
            """"description":"Optional id: lowercase letters, digits and '-', at most 32"}}}"""

    private const val OPEN_SCHEMA =
        """{"type":"object","properties":{""" +
            """"profileId":{"type":"string"},""" +
            """"workspaceId":{"type":"string","description":"A saved Space of this window's profile"},""" +
            """"newProfileName":{"type":"string","description":"Create a profile with this name, then open it"},""" +
            AUTH_PROPERTY + "," + PLUGINS_PROPERTY + "}}"
}
