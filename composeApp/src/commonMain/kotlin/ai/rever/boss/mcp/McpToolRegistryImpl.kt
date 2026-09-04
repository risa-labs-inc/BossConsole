package ai.rever.boss.mcp

import ai.rever.boss.components.bars.horizontal.StatusMessageManager
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
 * - [permitted] short-circuits on `isAdmin`, so for an admin operator — the
 *   common case on a single-user desktop — the user-disabled set is the ONLY
 *   access control left (the README's "Guardrails for the infrastructure
 *   plugins" section says so publicly). Its persistence therefore fails CLOSED
 *   on both the read and the write path, and every failure is published as an
 *   [McpKillSwitchFault] instead of costing one WARN line (BossConsole#85).
 *   See [McpToolRegistryCore.setToolEnabled] and [McpToolRegistryCore.fault].
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
    /**
     * What a client prepends to a registered tool name: `git_status` is typed as
     * `mcp__boss__git_status`.
     *
     * Assigned by the client's own config - the name it files this server under - not by anything
     * here, which is exactly why it belongs beside the registry rather than in whichever surface
     * happens to display it. Any surface that shows a tool the way a client types it should use
     * this, so all of them move together if that name ever does.
     */
    const val CLIENT_TOOL_PREFIX = "mcp__boss__"

    /** How long a kill-switch fault sits in the bottom bar — longer than a routine status message. */
    private const val FAULT_MESSAGE_MS = 10_000L

    private val core =
        McpToolRegistryCore(
            disabledFile = BossDirectories.resolve("mcp-disabled-tools.json"),
            // The Toolbox MCP tab lives in a plugin and cannot see [killSwitchFault]
            // without an api release, so the host announces the event itself. The
            // durable surface is the flow below, read by the status bar.
            onFault = { StatusMessageManager.showMessage(it.message, durationMs = FAULT_MESSAGE_MS) },
        )

    override val allTools: StateFlow<List<RegisteredMcpTool>> get() = core.allTools
    override val disabledToolNames: StateFlow<Set<String>> get() = core.disabledToolNames
    override val tools: StateFlow<List<RegisteredMcpTool>> get() = core.tools

    /**
     * Non-null while the kill-switch's persisted state cannot be trusted — a
     * damaged file on disk, or a toggle that could not be written. Rendered by
     * `BossRightBottomBar` for as long as it stays set, which is until a write
     * succeeds; a transient status message can't carry a fail-closed state, since
     * the next message cancels it and one raised during startup would be gone
     * before anyone looked. See [McpKillSwitchFault].
     */
    val killSwitchFault: StateFlow<McpKillSwitchFault?> get() = core.fault

    /** See `Core.permittedTools`. */
    fun permittedTools(): List<RegisteredMcpTool> = core.permittedTools()

    fun registerProvider(provider: McpToolProvider) = core.registerProvider(provider)

    fun unregisterProvider(providerId: String) = core.unregisterProvider(providerId)

    override fun setToolEnabled(
        toolName: String,
        enabled: Boolean,
    ) = core.setToolEnabled(toolName, enabled)

    /**
     * Update the current user's RBAC state. Pushed by the host (DynamicPluginManager's
     * access collector) on login / role change so permission-gated tools appear or
     * disappear live. Mirrors `pluginAccessAllowed` semantics (admin bypass).
     */
    fun updateAccess(
        isAdmin: Boolean,
        permissions: Set<String>,
    ) = core.updateAccess(isAdmin, permissions)

    override suspend fun invoke(
        toolName: String,
        arguments: String,
    ): McpToolResult = core.invoke(toolName, arguments)
}

/**
 * Why the per-tool MCP kill-switch cannot currently be trusted.
 *
 * The kill-switch ([McpToolRegistryCore.disabledToolNames]) is the only access
 * control an admin user does not bypass, so a persistence failure is a security
 * event rather than a cosmetic one. Both I/O paths used to fail open in silence
 * (BossConsole#85); they now fail closed and publish one of these.
 */
sealed interface McpKillSwitchFault {
    /** One-line operator-facing summary, suitable for a status message or banner. */
    val message: String

    /**
     * The disabled-tools file exists but could not be parsed, so the operator's
     * list is not trustworthy. Every registered tool is withheld — and *reported*
     * as disabled through [McpToolRegistryCore.disabledToolNames], which is what
     * keeps the Toolbox switch live so the operator can turn tools back on and
     * rebuild the file (see [McpToolRegistryCore.setToolEnabled]).
     *
     * [salvagedNames] is how many tool names were recovered from the damaged text
     * (truncation, the usual corruption, keeps most of them) — those stay disabled
     * across the rebuild. [quarantinePath] is the copy of the damaged bytes, or
     * `null` if even that could not be written; the original file is left in place
     * either way until the operator's first successful toggle rewrites it.
     */
    data class PersistedSetUnreadable(
        val path: String,
        val quarantinePath: String?,
        val salvagedNames: Int,
        val error: String,
    ) : McpKillSwitchFault {
        override val message: String
            get() =
                buildString {
                    append("All MCP tools are withheld: $path could not be read ($error). ")
                    append("Turn the tools you want back on in Toolbox → MCP to rebuild it")
                    append(if (quarantinePath != null) "; the damaged file is kept at $quarantinePath." else ".")
                }
    }

    /**
     * A toggle could not be written to disk. [outcome] says what was done about
     * it — the three cases are genuinely different, and reporting the wrong one is
     * the misinformation BossConsole#85 exists to remove.
     */
    data class TogglePersistFailed(
        val toolName: String,
        val enabled: Boolean,
        val outcome: ToggleOutcome,
        val error: String,
    ) : McpKillSwitchFault {
        override val message: String
            get() =
                "Could not save MCP tool settings ($error): " +
                    when (outcome) {
                        ToggleOutcome.NO_CHANGE -> "nothing changed for '$toolName'."
                        ToggleOutcome.APPLIED_SESSION_ONLY -> "'$toolName' is ${onOff(enabled)} for this session only."
                        ToggleOutcome.REFUSED -> "'$toolName' stays disabled."
                    }

        private fun onOff(enabled: Boolean) = if (enabled) "on again" else "off"
    }

    /** What an unpersistable toggle actually did. */
    enum class ToggleOutcome {
        /** The request was already true of the current set: nothing to lose. */
        NO_CHANGE,

        /**
         * Applied in memory but not on disk. Only ever reached when the result
         * still withholds everything the persisted record withholds — a disable,
         * or the undo of a disable that was itself never persisted.
         */
        APPLIED_SESSION_ONLY,

        /**
         * Rejected. Applying it would have exposed a tool the persisted record
         * says is disabled, which is the one outcome that widens what agents can
         * reach on the strength of state nobody recorded.
         */
        REFUSED,
    }
}

/**
 * Whether [def] may be run by a user with [isAdmin] and [permissions]: admin bypasses;
 * `requiresAdmin` gates to admins; otherwise every required permission must be held.
 *
 * A free function rather than a method, so the rule can be tested without the singleton - which
 * cannot be constructed without reading its disabled-tools file from `~/.boss`. That matters more
 * here than for most predicates: this is the gate that decides whether the names and full
 * descriptions of admin-only tools are enumerable by anyone typing into the double-shift search,
 * and while it lived inside the object it had no test at all.
 *
 * Note the default posture it implies: before anyone signs in, [isAdmin] is false and [permissions]
 * is empty, so a tool with no `requiredPermissions` and `requiresAdmin = false` IS permitted. That
 * is deliberate - see [McpToolRegistryImpl]'s "Security posture" - because this registry backs a
 * loopback-only server for the local machine's own agents.
 */
internal fun mcpToolPermitted(
    def: McpToolDefinition,
    isAdmin: Boolean,
    permissions: Set<String>,
): Boolean =
    when {
        isAdmin -> true
        def.requiresAdmin -> false
        else -> permissions.containsAll(def.requiredPermissions)
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
 * [onFault] is how a kill-switch persistence failure reaches the operator (the
 * façade turns it into a status-bar message); it is also mirrored into [fault].
 */
internal class McpToolRegistryCore(
    private val disabledFile: File?,
    private val invokeTimeoutMs: Long = 60_000L,
    private val onFault: (McpKillSwitchFault) -> Unit = {},
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

    /** Quoted identifier-shaped tokens — how [salvageToolNames] reads a damaged file. */
    private val salvageableName = Regex("\"([A-Za-z0-9_.:\\-]{1,120})\"")

    // Declared before _disabled on purpose: loadDisabled() writes all three of
    // these from _disabled's initializer, and Kotlin initializes in declaration
    // order. Nothing here calls out to [onFault]: the constructor must not hand
    // a half-built `this` to host code (the flow is the load-time surface).
    private val _fault = MutableStateFlow<McpKillSwitchFault?>(null)

    /**
     * Non-null while the kill-switch's persisted state is untrustworthy — the
     * durable, operator-facing explanation of a fail-closed state, rendered by
     * the host status bar. Sticky: only a successful write clears it, and a
     * single-tool write failure does NOT replace a [McpKillSwitchFault
     * .PersistedSetUnreadable], because "every tool is withheld" is the bigger
     * truth to keep on screen. See [McpKillSwitchFault].
     */
    val fault: StateFlow<McpKillSwitchFault?> = _fault.asStateFlow()

    /**
     * Set when [loadDisabled] found a file it could not parse. While it is up,
     * [applyExposed] folds every registered tool name into [_disabled], so:
     * nothing is exposed or invocable (fail closed); [disabledToolNames] reports
     * the withheld set truthfully, which keeps the Toolbox switch clickable — the
     * shipped MCP tab greys a tool out when it is neither exposed nor in that set,
     * and mislabels it "no permission"; and [setToolEnabled] rebuilds the file
     * from the withheld set rather than from nothing, so turning one tool back on
     * cannot re-enable the rest. Cleared only by a successful write.
     */
    @Volatile
    private var persistedSetUnreadable = false

    /**
     * The disabled set as far as the persisted record is concerned: what parsed at
     * startup, what was salvaged from a damaged file, or the last set successfully
     * written. [applyUnpersistedToggle] refuses any change that would stop
     * withholding something in here, which is what makes "never expose a tool the
     * record says is disabled" hold even when writes are failing.
     */
    @Volatile
    private var lastPersisted: Set<String> = emptySet()

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
    //
    // What is being withheld, which is what the management UI must show and what
    // the next write records — NOT purely "what the operator clicked". While
    // [persistedSetUnreadable] is up it also covers every registered tool (see
    // [applyExposed]); a rebuild therefore starts from the withheld set.
    private val _disabled = MutableStateFlow(loadDisabled())
    val disabledToolNames: StateFlow<Set<String>> = _disabled.asStateFlow()

    /**
     * Current user's RBAC state, pushed by the host (see [updateAccess]); gates tool exposure.
     *
     * Already `@Volatile` before the global search existed, which is what makes the new reader
     * safe: `permittedTools()` is now called from the search's supplier inside an `async` on
     * `Dispatchers.Default`, so the write needs a happens-before edge to a thread the writer does
     * not drive. Without it the staleness would fail OPEN - a search dispatched right after
     * sign-out filtered against the previous session's permissions - which is the wrong direction
     * for the field deciding whether admin-only tool names are enumerable.
     *
     * The two are volatile individually and NOT read as a pair: [updateAccess] writes [isAdmin]
     * then [permissions] outside any lock a reader takes, so a reader can see the new flag with
     * the old set. The window is one dispatch wide and only matters for permission-gated (not
     * admin-gated) tools, which is why it is documented rather than locked.
     *
     * **It fails OPEN**, and that is the part to carry forward if this reasoning is ever copied
     * somewhere else: the exposed set during that window is the outgoing session's, so a tool the
     * new state would deny can still be listed for one dispatch after a sign-out. Tolerable here
     * because the registry backs a loopback-only server for the local machine's own agents; not
     * tolerable in a context where the reader is a remote caller.
     */
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
        val defs =
            try {
                provider.tools()
            } catch (t: Throwable) {
                logger.warn(
                    LogCategory.SYSTEM,
                    "MCP provider tools() failed; registering with no tools",
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
                    LogCategory.SYSTEM,
                    "MCP tool provider re-registered (replacing previous)",
                    mapOf("providerId" to provider.providerId),
                )
            }
            _providers.update { it + (provider.providerId to defs) }
            recompute()
        }
        logger.info(
            LogCategory.SYSTEM,
            "MCP tool provider registered",
            mapOf("providerId" to provider.providerId, "tools" to defs.size),
        )
    }

    fun unregisterProvider(providerId: String): Unit =
        synchronized(mutationLock) {
            if (!_providers.value.containsKey(providerId)) return@synchronized
            _providers.update { it - providerId }
            recompute()
            logger.info(
                LogCategory.SYSTEM,
                "MCP tool provider unregistered",
                mapOf("providerId" to providerId),
            )
        }

    /**
     * Toggle one tool's kill-switch, **write-through**: the file is written first,
     * and the in-memory set changes only on the terms that write allows.
     *
     * The set the toggle is computed against is [disabledToolNames] — exactly what
     * the Toolbox switch showed the operator. That matters while
     * [persistedSetUnreadable] is up, where it is the *withheld* set (every
     * registered tool plus whatever was salvaged): turning one tool back on then
     * rebuilds the file with everything else still disabled, instead of writing a
     * file containing only the tool that was clicked.
     *
     * - Write succeeded: apply it, record it as [lastPersisted], clear [fault] and
     *   lift the gate. This is the recovery path out of an unreadable file.
     * - Write failed: see [applyUnpersistedToggle] — the outcome depends on
     *   whether applying it anyway would expose something the persisted record
     *   says is disabled, never on the button that was pressed.
     *
     * So while writes are failing: a disable applies for the session, undoing that
     * same unpersisted disable is allowed (it converges back on what the record
     * says rather than past it), and re-enabling something the record itself
     * withholds is refused until a write lands.
     *
     * [onFault] is invoked after the lock is released, so host UI never runs under
     * the mutation lock (see [mutationLock]).
     */
    fun setToolEnabled(
        toolName: String,
        enabled: Boolean,
    ) {
        val fault = synchronized(mutationLock) { applyToggle(toolName, enabled) }
        if (fault != null) notifyFault(fault)
    }

    /** [setToolEnabled]'s body, under [mutationLock]; returns a fault to announce, if any. */
    private fun applyToggle(
        toolName: String,
        enabled: Boolean,
    ): McpKillSwitchFault? {
        val previous = _disabled.value
        val next = if (enabled) previous - toolName else previous + toolName
        val failure = persistDisabled(next)
        if (failure != null) return applyUnpersistedToggle(toolName, enabled, previous, next, failure)
        _disabled.value = next
        lastPersisted = next
        persistedSetUnreadable = false
        _fault.value = null
        applyExposed()
        logger.info(
            LogCategory.SYSTEM,
            "MCP tool ${if (enabled) "enabled" else "disabled"}",
            mapOf("tool" to toolName),
        )
        return null
    }

    /**
     * The toggle could not be persisted. The invariant that decides what happens
     * is *not* which button was pressed: it is that nothing in [lastPersisted] may
     * stop being withheld on the strength of a change nobody recorded. Everything
     * that keeps withholding it is applied for the session (and announced as such);
     * anything else is refused.
     */
    private fun applyUnpersistedToggle(
        toolName: String,
        enabled: Boolean,
        previous: Set<String>,
        next: Set<String>,
        error: String,
    ): McpKillSwitchFault {
        val outcome =
            when {
                next == previous -> McpKillSwitchFault.ToggleOutcome.NO_CHANGE
                next.containsAll(lastPersisted) -> McpKillSwitchFault.ToggleOutcome.APPLIED_SESSION_ONLY
                else -> McpKillSwitchFault.ToggleOutcome.REFUSED
            }
        if (outcome == McpKillSwitchFault.ToggleOutcome.APPLIED_SESSION_ONLY) {
            _disabled.value = next
            applyExposed()
        }
        logger.error(
            LogCategory.SYSTEM,
            "MCP kill-switch change could not be persisted",
            mapOf(
                "tool" to toolName,
                "enabled" to enabled,
                "outcome" to outcome.name,
                "path" to disabledFile?.path,
                "error" to error,
            ),
        )
        val fault = McpKillSwitchFault.TogglePersistFailed(toolName, enabled, outcome, error)
        // A single tool's problem must not displace the "every tool is withheld"
        // explanation on screen; the notification below still reports it.
        if (!persistedSetUnreadable) _fault.value = fault
        return fault
    }

    /**
     * Hand a fault to [onFault] — called with no lock held. A notifier that throws
     * (UI not up yet, for instance) must not take a security control down with it.
     */
    private fun notifyFault(fault: McpKillSwitchFault) {
        try {
            onFault(fault)
        } catch (t: Throwable) {
            logger.warn(
                LogCategory.SYSTEM,
                "MCP kill-switch fault notifier failed",
                mapOf("error" to (t.message ?: t::class.simpleName)),
            )
        }
    }

    fun updateAccess(
        isAdmin: Boolean,
        permissions: Set<String>,
    ) = synchronized(mutationLock) {
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
                        LogCategory.SYSTEM,
                        "Duplicate MCP tool name skipped",
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
     *
     * While [persistedSetUnreadable] is up, every registered tool name is folded
     * into the disabled set first, so the empty exposure falls out of the normal
     * filter and [disabledToolNames] reports what is actually being withheld. The
     * alternative — treating an unparseable file as "nothing disabled" — silently
     * re-enabled every tool (BossConsole#85); reporting the withhold as an empty
     * disabled set instead left the Toolbox switch greyed out and labelled "no
     * permission", i.e. fail-closed with no way back.
     */
    private fun applyExposed() {
        if (persistedSetUnreadable) {
            _disabled.value = _disabled.value + _all.value.map { it.definition.name }
        }
        val disabled = _disabled.value
        _tools.value = _all.value.filter { it.definition.name !in disabled && permitted(it.definition) }
    }

    /**
     * Every registered tool the CURRENT user may run, disabled ones included.
     *
     * Between [allTools] and [tools]: it keeps the ones a user has switched off, because a search
     * for a tool someone disabled should find it and say so, but drops the ones they have no
     * permission for. [allTools] deliberately drops neither, which is right for the management UI
     * that shows every tool with its state, and wrong anywhere a name and a full description would
     * be enumerable by whoever is typing.
     */
    fun permittedTools(): List<RegisteredMcpTool> = _all.value.filter { permitted(it.definition) }

    /** Mirrors host RBAC. The rule itself is [mcpToolPermitted], which is where it is tested. */
    private fun permitted(def: McpToolDefinition): Boolean = mcpToolPermitted(def, isAdmin, permissions)

    suspend fun invoke(
        toolName: String,
        arguments: String,
    ): McpToolResult {
        // Only enabled tools are reachable (the bridge exposes exactly _tools).
        val tool =
            _tools.value.firstOrNull { it.definition.name == toolName }
                ?: return McpToolResult("Unknown or disabled MCP tool: $toolName", isError = true)
        val args = parseArgs(arguments)

        // Start tracing, catching trace errors so they don't break the tool
        val traceId = try {
            ai.rever.boss.components.observability.AgentTraceStore.startTrace(toolName, arguments)
        } catch (t: Throwable) {
            "untraced"
        }

        return try {
            val result = withTimeout(invokeTimeoutMs) { tool.definition.handler.call(args) }
            if (traceId != "untraced") {
                try {
                    ai.rever.boss.components.observability.AgentTraceStore.completeTrace(traceId, result)
                } catch (_: Throwable) {}
            }
            result
        } catch (t: TimeoutCancellationException) {
            logger.warn(
                LogCategory.SYSTEM,
                "MCP tool handler timed out",
                mapOf("tool" to toolName, "providerId" to tool.providerId, "timeoutMs" to invokeTimeoutMs),
                error = t,
            )
            if (traceId != "untraced") {
                try {
                    ai.rever.boss.components.observability.AgentTraceStore.failTrace(traceId, t, isTimeout = true)
                } catch (_: Throwable) {}
            }
            McpToolResult("Tool '$toolName' timed out after ${invokeTimeoutMs / 1000}s", isError = true)
        } catch (t: CancellationException) {
            // Caller cancellation (not our timeout) must propagate — swallowing it
            // would break structured concurrency during request cancel/shutdown.
            if (traceId != "untraced") {
                try {
                    ai.rever.boss.components.observability.AgentTraceStore.failTrace(traceId, t, isTimeout = false, isCancelled = true)
                } catch (_: Throwable) {}
            }
            throw t
        } catch (t: Throwable) {
            logger.warn(
                LogCategory.SYSTEM,
                "MCP tool handler failed",
                mapOf(
                    "tool" to toolName,
                    "providerId" to tool.providerId,
                    "error" to (t.message ?: t::class.simpleName),
                ),
            )
            if (traceId != "untraced") {
                try {
                    ai.rever.boss.components.observability.AgentTraceStore.failTrace(traceId, t, isTimeout = false)
                } catch (_: Throwable) {}
            }
            McpToolResult("Tool '$toolName' failed: ${t.message ?: t::class.simpleName}", isError = true)
        }
    }

    /**
     * Read the persisted disabled set. "Absent" and "unparseable" are NOT the same
     * answer: absent means the operator has disabled nothing, unparseable means
     * their list cannot be trusted. The second case used to collapse into
     * `emptySet()` behind one WARN, which re-enabled every tool (BossConsole#85);
     * it now goes through [degradeToFailClosed].
     */
    private fun loadDisabled(): Set<String> {
        val file = disabledFile
        if (file == null || !file.exists()) return emptySet()
        var raw: String? = null
        return try {
            raw = file.readText()
            json.decodeFromString<List<String>>(raw).toSet().also { lastPersisted = it }
        } catch (t: Throwable) {
            degradeToFailClosed(file, raw, t.message ?: t::class.simpleName ?: "unknown error")
        }
    }

    /**
     * Enter the fail-closed state for a damaged disabled-tools file: preserve the
     * evidence, salvage what intent is still legible, withhold everything.
     *
     * Salvaging matters because the recovery path rewrites the file: without it,
     * the operator turning one tool back on would write a file containing only
     * that tool and re-enable everything they had disabled — the same fail-open,
     * one click later and unrecoverable. Truncation (a crash or a `>` mid-write)
     * is the realistic corruption and leaves nearly every name readable.
     *
     * The salvaged names are also [lastPersisted]: the record still withholds
     * them as far as anyone can tell, so an unpersistable enable of one of them
     * stays refused.
     */
    private fun degradeToFailClosed(
        file: File,
        raw: String?,
        error: String,
    ): Set<String> {
        val salvaged = salvageToolNames(raw)
        val quarantine = quarantineDamagedFile(file, raw)
        persistedSetUnreadable = true
        lastPersisted = salvaged
        logger.error(
            LogCategory.SYSTEM,
            "Disabled MCP tools file is unreadable - withholding every MCP tool until it is rebuilt",
            mapOf(
                "path" to file.path,
                "salvagedNames" to salvaged.size,
                "quarantine" to quarantine,
                "error" to error,
            ),
        )
        _fault.value = McpKillSwitchFault.PersistedSetUnreadable(file.path, quarantine, salvaged.size, error)
        return salvaged
    }

    /**
     * Best-effort recovery of tool names from text that is not valid JSON: every
     * quoted identifier-shaped token, in file order. Deliberately generous —
     * anything extra it picks up only ever *adds* to the withheld set, and
     * anything it misses is covered by [persistedSetUnreadable] withholding
     * everything until the operator rebuilds the file by hand.
     */
    private fun salvageToolNames(raw: String?): Set<String> =
        if (raw == null) {
            emptySet()
        } else {
            salvageableName.findAll(raw).mapTo(LinkedHashSet()) { it.groupValues[1] }
        }

    /**
     * Copy the damaged bytes next to the original as `<name>.corrupt` so the
     * operator's real list survives the rebuild that overwrites the file. The
     * original is NOT moved — it stays in place until an explicit toggle rewrites
     * it. Returns the quarantine path, or `null` if it could not be written (the
     * same broken disk that produced the corruption may refuse this too).
     */
    private fun quarantineDamagedFile(
        file: File,
        raw: String?,
    ): String? {
        if (raw == null) return null
        val target = File(file.parentFile, file.name + ".corrupt")
        return try {
            target.atomicWriteText(raw)
            target.path
        } catch (t: Throwable) {
            logger.warn(
                LogCategory.SYSTEM,
                "Could not preserve the damaged disabled-tools file",
                mapOf("path" to target.path, "error" to (t.message ?: t::class.simpleName)),
            )
            null
        }
    }

    /**
     * Persist [set], returning `null` on success or a short failure reason. Never
     * throws: [setToolEnabled] decides what a failure means. No configured file
     * means pure in-memory mode, which has no durability to lose, so that counts
     * as success.
     */
    private fun persistDisabled(set: Set<String>): String? {
        if (disabledFile == null) return null
        return try {
            // Unique-temp atomic write so a crash mid-write can't corrupt the
            // file and concurrent writers can't clobber each other.
            disabledFile.atomicWriteText(json.encodeToString(set.toList().sorted()))
            null
        } catch (t: Throwable) {
            t.message ?: t::class.simpleName ?: "unknown error"
        }
    }

    /** Parse a JSON-object arguments string into a typed [McpToolArgs] of scalars. */
    private fun parseArgs(arguments: String): McpToolArgs {
        val map: Map<String, Any?> =
            try {
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
    private fun scalarOf(el: JsonElement): Any? =
        when {
            el is JsonNull -> {
                null
            }

            el is JsonPrimitive -> {
                if (el.isString) {
                    el.content
                } else {
                    el.booleanOrNull ?: el.longOrNull ?: el.doubleOrNull ?: el.content
                }
            }

            else -> {
                el.toString()
            }
        }
}
