package ai.rever.boss.mcp

import ai.rever.boss.plugin.api.McpToolArgs
import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolRegistry
import ai.rever.boss.plugin.api.McpToolResult
import ai.rever.boss.plugin.api.RegisteredMcpTool
import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.utils.atomicWriteText
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.IOException

/**
 * Process-wide registry aggregating MCP tools contributed by active plugins.
 *
 * Modeled on [ai.rever.boss.search.SearchRegistryImpl]. Plugins register an
 * [McpToolProvider] through `PluginContext.registerMcpToolProvider()`
 * (see [ai.rever.boss.components.plugin.DefaultPlugin]); the host unregisters
 * automatically when a plugin is disabled/unloaded (see
 * [ai.rever.boss.components.plugin.TrackingPluginContext.unregisterAll]). The
 * MCP server bridge in the terminal-tab plugin observes [tools] and mirrors the
 * set onto the live MCP server, routing calls back through [invoke].
 *
 * [allTools] is every tool from active plugins; [tools] is that minus the
 * user-disabled [disabledToolNames] AND permission-denied tools. The Plugin
 * Manager's MCP tab reads [allTools]/[disabledToolNames] and calls
 * [setToolEnabled].
 *
 * **Security posture — read before changing gating logic:**
 * - [allTools] is deliberately NOT permission-filtered: the management UI shows
 *   every contributed tool's name/description regardless of the current user's
 *   permissions, mirroring the existing convention for whole-plugin gating in
 *   the Plugin Store tab (a locked plugin still shows "Ask an admin — Requires:
 *   ..."). This is metadata disclosure only, local to the signed-in user's own
 *   desktop app — it grants no invocation. [invoke] and the bridge (which
 *   mirrors [tools], not [allTools]) both enforce [permitted] strictly.
 * - Before any user is signed in, [isAdmin] is `false` and [permissions] is
 *   empty. A tool with empty `requiredPermissions` and `requiresAdmin = false`
 *   is therefore exposed and callable with NO signed-in user — by design, this
 *   registry backs a loopback-only (127.0.0.1) MCP server for the local
 *   machine's own agents, not a remote/multi-tenant gate. Tools that must never
 *   run without an authenticated user should set `requiresAdmin = true` or a
 *   `requiredPermissions` value no anonymous session can hold.
 *
 * All mutation goes through [McpToolRegistryCore], which serializes every
 * mutator + the recompute it triggers on one lock: callers arrive on different
 * dispatchers (plugin lifecycle on `Default` under the manager mutex, the auth
 * collector on `Main`), and an unsynchronized recompute could write a stale
 * snapshot last — leaving an unloaded plugin's tools invocable or a
 * permission-revoked tool exposed. First registration of a given tool name
 * wins; later duplicates are logged and skipped.
 */
object McpToolRegistryImpl : McpToolRegistry {
    private val core = McpToolRegistryCore(disabledFile = BossDirectories.resolve("mcp-disabled-tools.json"))

    override val allTools: StateFlow<List<RegisteredMcpTool>> get() = core.allTools
    override val disabledToolNames: StateFlow<Set<String>> get() = core.disabledToolNames
    override val tools: StateFlow<List<RegisteredMcpTool>> get() = core.tools

    fun registerProvider(provider: McpToolProvider) = core.registerProvider(provider)

    fun unregisterProvider(providerId: String) = core.unregisterProvider(providerId)

    override fun setToolEnabled(toolName: String, enabled: Boolean) = core.setToolEnabled(toolName, enabled)

    /**
     * Update the current user's RBAC state. Pushed by the host (DynamicPluginManager's
     * access collector) on login / role change so permission-gated tools appear or
     * disappear live. Mirrors `pluginAccessAllowed` semantics (admin bypass).
     */
    fun updateAccess(isAdmin: Boolean, permissions: Set<String>) = core.updateAccess(isAdmin, permissions)

    override suspend fun invoke(toolName: String, arguments: String): McpToolResult = core.invoke(toolName, arguments)
}

/**
 * Testable core behind [McpToolRegistryImpl]. Extracted so unit tests can
 * exercise the registration/permission/persistence/dispatch logic against a
 * throwaway instance and a temp file, instead of the process-wide singleton
 * (which resolves a real file under the user's `~/.boss` directory and would
 * make tests mutate live state / interfere with each other).
 *
 * [disabledFile] is nullable: passing `null` skips persistence entirely (pure
 * in-memory), which is convenient for tests that don't care about it.
 * [invokeTimeoutMs] defaults to production's 60s but is overridable so tests
 * can exercise the timeout path in milliseconds instead of actually waiting.
 */
internal class McpToolRegistryCore(
    private val disabledFile: File?,
    private val invokeTimeoutMs: Long = 60_000L,
) {
    private val logger = BossLogger.forComponent("McpToolRegistry")

    /**
     * Serializes all mutations + recomputes (see [McpToolRegistryImpl] KDoc).
     * Reads stay lock-free. Untrusted plugin code never runs under this lock:
     * [registerProvider] queries the provider's `tools()` BEFORE acquiring it
     * and caches the result, so a slow/blocking/throwing `tools()` can only
     * delay its own registration call — never another plugin's lifecycle, a
     * user toggle, or the Main-dispatcher auth collector behind [updateAccess].
     */
    private val mutationLock = Any()

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * providerId -> that provider's tool list, cached once at registration;
     * insertion order preserved for stable first-wins dedup. Caching (rather
     * than re-querying `tools()` on every recompute) is what makes the
     * documented snapshot-at-registration semantics literal, keeps recompute
     * O(total tools) instead of O(providers) plugin calls per change, and keeps
     * plugin code outside [mutationLock].
     */
    private val _providers = MutableStateFlow<Map<String, List<McpToolDefinition>>>(emptyMap())

    private val _all = MutableStateFlow<List<RegisteredMcpTool>>(emptyList())
    val allTools: StateFlow<List<RegisteredMcpTool>> = _all.asStateFlow()

    // Not pruned against currently-registered tools: a name toggled off by a
    // since-uninstalled plugin lingers here indefinitely. Deliberate — it
    // preserves the user's preference across a reinstall/reload — but it does
    // mean this set only ever grows for users who churn through many plugins.
    private val _disabled = MutableStateFlow(loadDisabled())
    val disabledToolNames: StateFlow<Set<String>> = _disabled.asStateFlow()

    /** Current user's RBAC state, pushed by the host (see [updateAccess]); gates tool exposure. */
    @Volatile
    private var isAdmin = false

    @Volatile
    private var permissions: Set<String> = emptySet()

    /** Enabled tools = registered minus user-disabled minus permission-denied. This is what the bridge mirrors. */
    private val _tools = MutableStateFlow<List<RegisteredMcpTool>>(emptyList())
    val tools: StateFlow<List<RegisteredMcpTool>> = _tools.asStateFlow()

    fun registerProvider(provider: McpToolProvider) {
        // Query the plugin's tools() OUTSIDE the lock — see mutationLock KDoc.
        // A throwing provider registers with an empty tool set (and a warning)
        // rather than being silently dropped: its id stays tracked so teardown
        // and re-registration behave normally.
        val defs = try {
            provider.tools()
        } catch (t: Throwable) {
            logger.warn(
                LogCategory.SYSTEM, "MCP provider tools() failed; registering with no tools",
                mapOf("providerId" to provider.providerId, "error" to (t.message ?: t::class.simpleName)),
            )
            emptyList()
        }
        synchronized(mutationLock) {
            if (_providers.value.containsKey(provider.providerId)) {
                // Same-id re-registration replaces the previous provider. Legitimate on
                // plugin reload, but worth a trace: two plugins sharing an id would
                // clobber each other and the first teardown would kill both tool sets.
                logger.warn(
                    LogCategory.SYSTEM, "MCP tool provider re-registered (replacing previous)",
                    mapOf("providerId" to provider.providerId),
                )
            }
            _providers.update { it + (provider.providerId to defs) }
            recompute()
        }
        logger.info(
            LogCategory.SYSTEM, "MCP tool provider registered",
            mapOf("providerId" to provider.providerId, "tools" to defs.size),
        )
    }

    fun unregisterProvider(providerId: String): Unit = synchronized(mutationLock) {
        if (!_providers.value.containsKey(providerId)) return@synchronized
        _providers.update { it - providerId }
        recompute()
        logger.info(
            LogCategory.SYSTEM, "MCP tool provider unregistered",
            mapOf("providerId" to providerId),
        )
    }

    fun setToolEnabled(toolName: String, enabled: Boolean) = synchronized(mutationLock) {
        _disabled.update { if (enabled) it - toolName else it + toolName }
        saveDisabled(_disabled.value)
        applyExposed()
        logger.info(
            LogCategory.SYSTEM, "MCP tool ${if (enabled) "enabled" else "disabled"}",
            mapOf("tool" to toolName),
        )
    }

    fun updateAccess(isAdmin: Boolean, permissions: Set<String>) = synchronized(mutationLock) {
        this.isAdmin = isAdmin
        this.permissions = permissions
        applyExposed()
    }

    /**
     * Flatten all *currently registered* providers' cached tool lists into
     * [allTools], deduping by name (first wins, in provider-registration
     * order). This runs fresh on every register/unregister — dedup is
     * per-recompute-pass, not a permanent claim: if provider A wins a name over
     * provider B and A is later unregistered, B's tool takes over on the next
     * recompute (B was never "permanently shadowed").
     *
     * Each provider's [McpToolProvider.tools] was queried exactly once, at its
     * own registration (see [registerProvider]) — NOT here and NOT reactively.
     * A provider whose desired tool set changes without a register/unregister
     * cycle will not be reflected until the next one (e.g. a plugin
     * disable/enable cycle). Pure flatten of cached lists: O(total tools), no
     * plugin code, safe under [mutationLock].
     */
    private fun recompute() {
        val snapshot = _providers.value
        val seen = HashSet<String>()
        val flat = ArrayList<RegisteredMcpTool>()
        for ((providerId, defs) in snapshot) {
            for (def in defs) {
                if (!seen.add(def.name)) {
                    logger.warn(
                        LogCategory.SYSTEM, "Duplicate MCP tool name skipped",
                        mapOf("providerId" to providerId, "tool" to def.name),
                    )
                    continue
                }
                flat.add(RegisteredMcpTool(providerId, def))
            }
        }
        _all.value = flat
        applyExposed()
    }

    /**
     * Recompute the exposed [tools] set: registered tools that are neither
     * user-disabled ([disabledToolNames]) nor permission-denied for the current
     * user. The bridge mirrors exactly this set.
     */
    private fun applyExposed() {
        val disabled = _disabled.value
        _tools.value = _all.value.filter { it.definition.name !in disabled && permitted(it.definition) }
    }

    /** Mirrors host RBAC: admin bypasses; requiresAdmin gates to admins; else must hold all perms. */
    private fun permitted(def: McpToolDefinition): Boolean {
        if (isAdmin) return true
        if (def.requiresAdmin) return false
        return permissions.containsAll(def.requiredPermissions)
    }

    suspend fun invoke(toolName: String, arguments: String): McpToolResult {
        // Only enabled tools are reachable (the bridge exposes exactly _tools).
        val tool = _tools.value.firstOrNull { it.definition.name == toolName }
            ?: return McpToolResult("Unknown or disabled MCP tool: $toolName", isError = true)
        val args = parseArgs(arguments)
        return try {
            withTimeout(invokeTimeoutMs) { tool.definition.handler.call(args) }
        } catch (t: TimeoutCancellationException) {
            logger.warn(
                LogCategory.SYSTEM, "MCP tool handler timed out",
                mapOf("tool" to toolName, "providerId" to tool.providerId, "timeoutMs" to invokeTimeoutMs),
                error = t,
            )
            McpToolResult("Tool '$toolName' timed out after ${invokeTimeoutMs / 1000}s", isError = true)
        } catch (t: CancellationException) {
            // Caller cancellation (not our timeout) must propagate — swallowing it
            // would break structured concurrency during request cancel/shutdown.
            throw t
        } catch (t: Throwable) {
            logger.warn(
                LogCategory.SYSTEM, "MCP tool handler failed",
                mapOf(
                    "tool" to toolName,
                    "providerId" to tool.providerId,
                    "error" to (t.message ?: t::class.simpleName),
                ),
            )
            McpToolResult("Tool '$toolName' failed: ${t.message ?: t::class.simpleName}", isError = true)
        }
    }

    private fun loadDisabled(): Set<String> {
        if (disabledFile == null) return emptySet()
        return try {
            if (disabledFile.exists()) json.decodeFromString<List<String>>(disabledFile.readText()).toSet()
            else emptySet()
        } catch (t: Throwable) {
            logger.warn(LogCategory.SYSTEM, "Failed to load disabled MCP tools", mapOf("error" to (t.message ?: "")))
            emptySet()
        }
    }

    private fun saveDisabled(set: Set<String>) {
        if (disabledFile == null) return
        try {
            // Unique-temp atomic write so a crash mid-write can't corrupt the
            // file (loadDisabled fails open to emptySet — silently re-enabling
            // tools) and concurrent writers can't clobber each other.
            disabledFile.atomicWriteText(json.encodeToString(set.toList().sorted()))
        } catch (t: Throwable) {
            logger.warn(LogCategory.SYSTEM, "Failed to persist disabled MCP tools", mapOf("error" to (t.message ?: "")))
        }
    }

    /** Parse a JSON-object arguments string into a typed [McpToolArgs] of scalars. */
    private fun parseArgs(arguments: String): McpToolArgs {
        val map: Map<String, Any?> = try {
            (json.parseToJsonElement(arguments) as? JsonObject)
                ?.mapValues { (_, el) -> scalarOf(el) }
                ?: emptyMap()
        } catch (t: Throwable) {
            logger.debug(
                LogCategory.SYSTEM,
                "MCP tool arguments are not a JSON object - using empty args",
                mapOf("error" to t.toString()),
            )
            emptyMap()
        }
        return McpToolArgs(map, arguments.ifBlank { "{}" })
    }

    /** Convert a JSON element to a Kotlin scalar; nested objects/arrays become their raw JSON. */
    private fun scalarOf(el: JsonElement): Any? = when {
        el is JsonNull -> null
        el is JsonPrimitive -> if (el.isString) {
            el.content
        } else {
            el.booleanOrNull ?: el.longOrNull ?: el.doubleOrNull ?: el.content
        }
        else -> el.toString()
    }
}
