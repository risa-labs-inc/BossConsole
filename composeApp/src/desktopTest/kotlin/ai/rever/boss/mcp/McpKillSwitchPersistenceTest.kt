package ai.rever.boss.mcp

import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The per-tool MCP kill-switch's persistence contract — BossConsole#85.
 *
 * [McpToolRegistryCore.permitted] short-circuits on `isAdmin`, so for an admin
 * operator (the common case on a single-user desktop) the user-disabled set is
 * the ONLY access control left. Both of its I/O paths used to fail open in
 * silence; these tests pin the fail-CLOSED replacement, and specifically the
 * three things that are easy to get wrong while doing it:
 *
 * - failing closed must not destroy or discard the operator's list (salvage +
 *   quarantine + rebuild from the withheld set);
 * - failing closed must leave a reachable way back (the Toolbox switch is driven
 *   off `tools`/`disabledToolNames`, and greys out when both go empty);
 * - what the operator is TOLD must match what happened, in all three
 *   [McpKillSwitchFault.ToggleOutcome] cases.
 *
 * Split out of [McpToolRegistryCoreTest] (which keeps RBAC gating, dedup and
 * dispatch) to keep either class a readable size. The small helper block below is
 * deliberately self-contained rather than shared, so neither file's fixtures can
 * drift under the other's tests.
 */
class McpKillSwitchPersistenceTest {
    private val tempFiles = mutableListOf<File>()

    /**
     * A realistically damaged disabled-tools file: valid JSON cut short mid-write,
     * so the tool names are still legible. Using unparseable text with NO readable
     * names would make the salvage and rebuild paths untestable — there would be
     * nothing for a rebuild to lose.
     */
    private val truncatedFile = "[\"k8s_exec\",\"docker_rm\""

    /** A throwaway disabled-tools file under the OS temp dir, cleaned up after each test. */
    private fun tempDisabledFile(): File {
        val dir =
            kotlin.io.path
                .createTempDirectory("mcp-killswitch-test")
                .toFile()
        return File(dir, "mcp-disabled-tools.json").also { tempFiles.add(it) }
    }

    /**
     * A path nothing can be written to, on any platform: its parent is a regular
     * file, so both `mkdirs()` and the sibling-temp-file creation inside
     * `atomicWriteText` fail. Preferred over chmod games, which Windows ignores.
     */
    private fun unwritablePath(): File {
        val blocker = tempDisabledFile()
        blocker.parentFile.mkdirs()
        blocker.writeText("not a directory")
        return File(blocker, "mcp-disabled-tools.json")
    }

    /** Break persistence for an already-constructed core: swap its directory for a regular file. */
    private fun breakWritesTo(file: File) {
        val dir = requireNotNull(file.parentFile)
        dir.deleteRecursively()
        dir.writeText("not a directory")
    }

    /** The published kill-switch fault, asserted to be a [T]. */
    private inline fun <reified T : McpKillSwitchFault> faultOf(core: McpToolRegistryCore): T =
        assertNotNull(core.fault.value as? T, "expected ${T::class.simpleName}, got ${core.fault.value}")

    @AfterTest
    fun cleanup() {
        tempFiles.forEach { it.parentFile?.deleteRecursively() }
        tempFiles.clear()
    }

    private fun provider(
        id: String,
        vararg defs: McpToolDefinition,
    ) = object : McpToolProvider {
        override val providerId = id

        override fun tools() = defs.toList()
    }

    private fun echoTool(name: String) =
        McpToolDefinition(
            name = name,
            description = "test tool $name",
            handler = McpToolHandler { McpToolResult("ok:$name") },
        )

    /**
     * The realistic corruption: a truncated write, so the names are still legible.
     * The fixture matters — a fixture with no readable names (the first version of
     * this test used `"{ not valid json list ]"`) hides whether the salvage and
     * rebuild paths preserve anything, because there is nothing to lose.
     *
     * A file we cannot parse does NOT mean "nothing is disabled" — it means the
     * operator's list cannot be trusted. Reading it as an empty set silently
     * re-enabled every tool, including for an admin, for whom the kill-switch is
     * the only control left (BossConsole#85).
     *
     * Was `corrupt disabled-tools file fails open to emptySet rather than crashing`,
     * which pinned the old behaviour.
     */
    @Test
    fun `corrupt disabled-tools file fails closed - every tool is withheld and the names are salvaged`() =
        runBlocking {
            val file = tempDisabledFile()
            file.parentFile.mkdirs()
            file.writeText(truncatedFile)
            val faults = mutableListOf<McpKillSwitchFault>()

            val core = McpToolRegistryCore(disabledFile = file, onFault = { faults.add(it) })
            core.registerProvider(provider("p1", echoTool("k8s_exec"), echoTool("docker_rm"), echoTool("safe")))
            // Admin buys no relief here: this is the one control admin doesn't bypass.
            core.updateAccess(isAdmin = true, permissions = emptySet())

            assertTrue(core.tools.value.isEmpty(), "expected no exposed tools, got ${core.tools.value}")
            assertTrue(core.invoke("safe", "{}").isError, "nothing may be invocable while the file is unreadable")
            assertEquals(3, core.allTools.value.size, "the management view still lists everything registered")

            val fault = faultOf<McpKillSwitchFault.PersistedSetUnreadable>(core)
            assertEquals(file.path, fault.path)
            assertEquals(2, fault.salvagedNames, "both legible names must be recovered from the damaged text")
            assertEquals(truncatedFile, file.readText(), "the damaged file is left in place, not moved or rewritten")
            assertTrue(
                faults.isEmpty(),
                "a load-time fault must not call out to host code from the constructor - the flow is the surface",
            )
        }

    /**
     * HIGH: fail-closed with no way out is not shippable. The Toolbox MCP tab
     * (`boss_plugins/plugin-manager/.../PluginManagerView.kt`, both toggle sites)
     * derives its switch as:
     *
     * ```
     * val on = name in exposedNames            // registry.tools
     * val userDisabled = name in disabled      // registry.disabledToolNames
     * val permissionDenied = !on && !userDisabled
     * BossToggle(checked = on, enabled = !permissionDenied, description = "… 🔒 no permission")
     * ```
     *
     * so withholding tools by emptying BOTH flows greys every switch out and tells
     * an admin their tools are blocked by missing permissions — with `setToolEnabled`
     * unreachable behind `!permissionDenied`. Reporting the withheld set through
     * `disabledToolNames` is what keeps the one gesture that recovers the file live.
     */
    @Test
    fun `while the file is unreadable every tool reads as user-disabled so the Toolbox switch stays live`() {
        val file = tempDisabledFile()
        file.parentFile.mkdirs()
        file.writeText(truncatedFile)

        val core = McpToolRegistryCore(disabledFile = file)
        core.registerProvider(provider("p1", echoTool("k8s_exec"), echoTool("safe")))

        val exposedNames =
            core.tools.value
                .map { it.definition.name }
                .toSet()
        val disabled = core.disabledToolNames.value
        assertEquals(2, core.allTools.value.size)
        core.allTools.value.forEach { tool ->
            val name = tool.definition.name
            val on = name in exposedNames
            val userDisabled = name in disabled
            assertFalse(on, "$name must not be exposed while the kill-switch is unreadable")
            assertTrue(userDisabled, "$name must report as user-disabled, or its switch is greyed out")
            assertFalse(!on && !userDisabled, "$name must not read as permission-denied")
        }
    }

    /**
     * HIGH: the recovery path must not widen exposure. The operator does exactly
     * what the fault message says — turns one tool back on — and that write rebuilds
     * the file. Before the salvage and the withheld-set semantics, `previous` was
     * the empty set, so the rebuild wrote a file containing ONLY the clicked tool:
     * every previously-disabled tool came back live, the evidence was gone, and it
     * was logged as a routine INFO.
     */
    @Test
    fun `rebuilding after an unreadable file keeps the salvaged tools disabled`() =
        runBlocking {
            val file = tempDisabledFile()
            file.parentFile.mkdirs()
            file.writeText(truncatedFile)
            val core = McpToolRegistryCore(disabledFile = file)
            core.registerProvider(provider("p1", echoTool("k8s_exec"), echoTool("docker_rm"), echoTool("safe")))
            core.updateAccess(isAdmin = true, permissions = emptySet())
            assertTrue(core.tools.value.isEmpty(), "precondition: the unreadable file withholds everything")

            core.setToolEnabled("safe", enabled = true)

            assertNull(core.fault.value, "a durable write means the persisted state can be trusted again")
            assertEquals(
                listOf("safe"),
                core.tools.value.map { it.definition.name },
                "only the tool the operator turned on may come back",
            )
            assertTrue(core.invoke("k8s_exec", "{}").isError, "the salvaged tools stay uninvocable, admin included")
            assertEquals(
                setOf("k8s_exec", "docker_rm"),
                McpToolRegistryCore(disabledFile = file).disabledToolNames.value,
                "and the rebuilt file still records them as disabled",
            )
        }

    /**
     * The rebuild overwrites the damaged file, so the operator's real list has to
     * survive somewhere: the raw bytes are copied to a `.corrupt` sidecar at load
     * time, before anything can overwrite them, and the copy is not touched again.
     */
    @Test
    fun `the damaged file is preserved as a corrupt sidecar and survives the rebuild`() {
        val file = tempDisabledFile()
        file.parentFile.mkdirs()
        file.writeText(truncatedFile)
        val sidecar = File(file.parentFile, file.name + ".corrupt")

        val core = McpToolRegistryCore(disabledFile = file)
        core.registerProvider(provider("p1", echoTool("k8s_exec"), echoTool("safe")))
        assertTrue(sidecar.exists(), "the damaged bytes must be preserved before any rebuild")
        assertEquals(truncatedFile, sidecar.readText())

        core.setToolEnabled("safe", enabled = true)

        assertEquals(truncatedFile, sidecar.readText(), "the rebuild must not disturb the preserved copy")
        assertTrue(file.readText().contains("k8s_exec"), "and the rebuilt file is valid JSON with the salvaged names")
    }

    /**
     * The contrast case: absent is not corrupt. No file means the operator has
     * disabled nothing, so exposure proceeds normally and no fault is raised.
     */
    @Test
    fun `missing disabled-tools file starts with an empty disabled set and no fault`() {
        val file = tempDisabledFile()
        assertFalse(file.exists())
        val faults = mutableListOf<McpKillSwitchFault>()

        val core = McpToolRegistryCore(disabledFile = file, onFault = { faults.add(it) })
        core.registerProvider(provider("p1", echoTool("t1")))

        assertEquals(emptySet(), core.disabledToolNames.value)
        assertNull(core.fault.value, "an absent file is the normal state, not a degraded one")
        assertTrue(faults.isEmpty())
        assertTrue(core.tools.value.any { it.definition.name == "t1" }, "the fail-closed gate must not trip")
    }

    /**
     * The other gesture available while the gate is up. Every switch shows "off",
     * so clicking one is an enable (covered above); a *disable* — from a plugin
     * calling the api, or a second click — writes the withheld set as it stands.
     * That ratifies "everything off", which is safe, clears the degraded state, and
     * leaves the operator in the ordinary flow where tools can be turned back on
     * one at a time. What it must NOT do is write a file that drops the withheld
     * names.
     */
    @Test
    fun `a disable while the gate is up ratifies the withheld set and leaves no dead end`() {
        val file = tempDisabledFile()
        file.parentFile.mkdirs()
        file.writeText(truncatedFile)
        val core = McpToolRegistryCore(disabledFile = file)
        core.registerProvider(provider("p1", echoTool("k8s_exec"), echoTool("safe")))
        assertTrue(core.tools.value.isEmpty(), "precondition: the unreadable file withholds everything")

        core.setToolEnabled("safe", enabled = false)

        assertNull(core.fault.value, "a durable write means the persisted state can be trusted again")
        assertTrue(core.tools.value.isEmpty(), "everything stays withheld - that is what was written")
        assertEquals(
            setOf("k8s_exec", "docker_rm", "safe"),
            McpToolRegistryCore(disabledFile = file).disabledToolNames.value,
            "the rebuilt file must keep the salvaged names, not just the clicked one",
        )

        // And the operator is not stuck: the normal flow works from here.
        core.setToolEnabled("safe", enabled = true)
        assertEquals(listOf("safe"), core.tools.value.map { it.definition.name })
    }

    /**
     * The withhold-all gate is tracked separately from the published fault, so a
     * later single-tool write failure can neither lift it nor displace the
     * explanation for it: "every tool is withheld" is the bigger truth, and it is
     * what the status bar must keep showing. The one-tool problem is still
     * announced through the notifier, which is where events belong.
     */
    @Test
    fun `a later persist failure keeps both the withhold-all gate and its explanation`() {
        val file = tempDisabledFile()
        file.parentFile.mkdirs()
        file.writeText(truncatedFile)
        val faults = mutableListOf<McpKillSwitchFault>()
        val core = McpToolRegistryCore(disabledFile = file, onFault = { faults.add(it) })
        core.registerProvider(provider("p1", echoTool("k8s_exec"), echoTool("safe")))
        breakWritesTo(file)

        core.setToolEnabled("safe", enabled = false)

        assertTrue(
            core.fault.value is McpKillSwitchFault.PersistedSetUnreadable,
            "the withhold-all explanation must stay on screen, got ${core.fault.value}",
        )
        assertTrue(core.tools.value.isEmpty(), "and the tools stay withheld, got ${core.tools.value}")
        assertTrue(
            faults.any { it is McpKillSwitchFault.TogglePersistFailed },
            "the write failure is still announced as an event",
        )
    }

    /**
     * Write side, restricting direction. The disable still applies to this session
     * — rolling it back would leave the tool live for agents *right now*, and
     * restricting is the safe direction — but the silence is gone: the failure is
     * an ERROR plus a sticky [McpKillSwitchFault] the host turns into a status
     * message, so "this will not survive a restart" is visible.
     *
     * Persistence itself cannot be rescued when the path is unwritable, so the
     * final assertion still shows the decision missing on the next launch; what
     * BossConsole#85 changed is that it is no longer unannounced. Was
     * `unwritable disabled-tools file still toggles in memory but does not persist`,
     * which asserted "no signal" as the contract.
     */
    @Test
    fun `a disable that cannot be persisted still applies but is reported as unpersisted`() {
        val unwritable = unwritablePath()
        val faults = mutableListOf<McpKillSwitchFault>()

        val core = McpToolRegistryCore(disabledFile = unwritable, onFault = { faults.add(it) })
        core.registerProvider(provider("p1", echoTool("doomed_toggle")))

        // Must not throw, even though persisting is impossible.
        core.setToolEnabled("doomed_toggle", enabled = false)

        assertEquals(
            setOf("doomed_toggle"),
            core.disabledToolNames.value,
            "the in-memory toggle must still apply for this session",
        )
        assertFalse(
            core.tools.value.any { it.definition.name == "doomed_toggle" },
            "the tool must be hidden for this session",
        )
        // Assert the write genuinely failed rather than landing somewhere else --
        // otherwise the reload assertion below could pass for the wrong reason.
        assertFalse(unwritable.exists(), "the unwritable path must not have been created")

        val fault = faultOf<McpKillSwitchFault.TogglePersistFailed>(core)
        assertEquals("doomed_toggle", fault.toolName)
        assertFalse(fault.enabled)
        assertEquals(
            McpKillSwitchFault.ToggleOutcome.APPLIED_SESSION_ONLY,
            fault.outcome,
            "a disable is kept for the session, not rolled back",
        )
        assertEquals(1, faults.size, "the operator must be told, not just the log file")
        assertEquals(
            emptySet(),
            McpToolRegistryCore(disabledFile = unwritable).disabledToolNames.value,
            "unwritable stays unwritable across launches - the fault above is the signal that stops it being silent",
        )
    }

    /**
     * Write side, widening direction — the half that must be refused. Re-enabling a
     * tool the persisted record says is disabled, on the strength of a write nobody
     * recorded, is the only outcome that grows what agents can reach. Memory, disk
     * and UI stay in agreement (the Toolbox switch renders `checked = name in
     * registry.tools`, so a refused enable leaves it showing "off" on its own).
     */
    @Test
    fun `a re-enable that cannot be persisted is refused so the tool stays disabled`() =
        runBlocking {
            val file = tempDisabledFile()
            file.parentFile.mkdirs()
            file.writeText("""["locked_tool"]""")
            val faults = mutableListOf<McpKillSwitchFault>()

            val core = McpToolRegistryCore(disabledFile = file, onFault = { faults.add(it) })
            core.registerProvider(provider("p1", echoTool("locked_tool")))
            core.updateAccess(isAdmin = true, permissions = emptySet())
            assertFalse(
                core.tools.value.any { it.definition.name == "locked_tool" },
                "precondition: the persisted set loaded and the tool is disabled",
            )

            // Break persistence only now, so the load above succeeded on real bytes.
            breakWritesTo(file)
            core.setToolEnabled("locked_tool", enabled = true)

            assertEquals(
                setOf("locked_tool"),
                core.disabledToolNames.value,
                "an enable we cannot record must be refused",
            )
            assertFalse(core.tools.value.any { it.definition.name == "locked_tool" }, "the tool must stay hidden")
            assertTrue(core.invoke("locked_tool", "{}").isError, "and stay uninvocable, admin included")

            val fault = faultOf<McpKillSwitchFault.TogglePersistFailed>(core)
            assertTrue(fault.enabled)
            assertEquals(McpKillSwitchFault.ToggleOutcome.REFUSED, fault.outcome)
            assertEquals(1, faults.size)
        }

    /**
     * The mirror of the refusal: undoing a disable that was itself never persisted
     * is allowed, because it converges back on what the record says rather than
     * widening past it. Without this, one accidental click during a disk problem
     * could only be undone by restarting the app.
     */
    @Test
    fun `undoing a session-only disable is allowed while persistence is broken`() {
        val unwritable = unwritablePath()
        val core = McpToolRegistryCore(disabledFile = unwritable)
        core.registerProvider(provider("p1", echoTool("oops")))

        core.setToolEnabled("oops", enabled = false)
        assertEquals(setOf("oops"), core.disabledToolNames.value, "precondition: disabled for this session only")

        core.setToolEnabled("oops", enabled = true)

        assertEquals(emptySet(), core.disabledToolNames.value, "the unsaved disable must be undoable")
        assertTrue(core.tools.value.any { it.definition.name == "oops" }, "and the tool comes back")
        val fault = faultOf<McpKillSwitchFault.TogglePersistFailed>(core)
        assertEquals(McpKillSwitchFault.ToggleOutcome.APPLIED_SESSION_ONLY, fault.outcome)
        assertTrue(fault.enabled)
    }

    /**
     * The outcome must come from what actually happened, not from a set diff. An
     * enable of a tool that was never disabled changes nothing, and the earlier
     * two-state logic (`rolledBack = enabled && next != previous`) fell through to
     * the session-only branch and told the operator the tool was "disabled for this
     * session only" while it stayed fully exposed — the misinformation class #85
     * exists to remove.
     */
    @Test
    fun `an unpersistable no-op enable is reported as no change, not as a session-only disable`() {
        val unwritable = unwritablePath()
        val core = McpToolRegistryCore(disabledFile = unwritable)
        core.registerProvider(provider("p1", echoTool("already_on")))

        core.setToolEnabled("already_on", enabled = true)

        assertTrue(core.tools.value.any { it.definition.name == "already_on" }, "the tool is still exposed")
        assertEquals(emptySet(), core.disabledToolNames.value)
        val fault = faultOf<McpKillSwitchFault.TogglePersistFailed>(core)
        assertEquals(McpKillSwitchFault.ToggleOutcome.NO_CHANGE, fault.outcome)
        assertTrue(
            fault.message.contains("nothing changed"),
            "the operator must not be told a live tool is disabled: ${fault.message}",
        )
    }

    /**
     * The fault notifier is host UI. A broken notifier must not take a security
     * control down with it, and the fault must still be published for anything
     * observing the flow.
     */
    @Test
    fun `a fault notifier that throws does not break the toggle`() {
        val unwritable = unwritablePath()
        val core = McpToolRegistryCore(disabledFile = unwritable, onFault = { error("notifier bug") })
        core.registerProvider(provider("p1", echoTool("t")))

        core.setToolEnabled("t", enabled = false)

        assertEquals(setOf("t"), core.disabledToolNames.value)
        assertNotNull(core.fault.value, "the fault is still published even though the notifier failed")
    }

    /**
     * `mutationLock`'s KDoc promises that host and plugin code never runs under it.
     * The notifier is host UI, so it must be called after the lock is released —
     * otherwise a slow status-bar update blocks every plugin lifecycle event and the
     * auth collector. Proven by having the notifier wait on another thread mutating
     * the same registry: under the lock, that thread cannot get in.
     */
    @Test
    fun `the fault notifier does not run under the mutation lock`() {
        val unwritable = unwritablePath()
        var coreRef: McpToolRegistryCore? = null
        var otherThreadMutated = false
        val core =
            McpToolRegistryCore(
                disabledFile = unwritable,
                onFault = {
                    val other =
                        Thread {
                            requireNotNull(coreRef).updateAccess(isAdmin = true, permissions = emptySet())
                            otherThreadMutated = true
                        }
                    other.start()
                    other.join(2_000)
                },
            )
        coreRef = core
        core.registerProvider(provider("p1", echoTool("t")))

        core.setToolEnabled("t", enabled = false)

        assertTrue(otherThreadMutated, "another thread must be able to mutate the registry while the notifier runs")
    }
}
