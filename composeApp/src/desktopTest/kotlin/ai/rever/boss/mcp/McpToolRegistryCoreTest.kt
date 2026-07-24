package ai.rever.boss.mcp

import ai.rever.boss.plugin.api.McpToolArgs
import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [McpToolRegistryCore] — the testable core behind the
 * process-wide [McpToolRegistryImpl] singleton. Covers the pure logic that 19
 * downstream plugins depend on: RBAC gating ([McpToolRegistryImpl] KDoc
 * "Security posture"), first-wins dedup across providers, disabled-set
 * persistence (including the fail-open-to-emptySet path), argument scalar
 * coercion, and [McpToolRegistryCore.invoke]'s timeout/cancellation/rejection
 * contract.
 */
class McpToolRegistryCoreTest {
    private val tempFiles = mutableListOf<File>()

    /** A throwaway disabled-tools file under the OS temp dir, cleaned up after each test. */
    private fun tempDisabledFile(): File {
        val dir =
            kotlin.io.path
                .createTempDirectory("mcp-registry-test")
                .toFile()
        return File(dir, "mcp-disabled-tools.json").also { tempFiles.add(it) }
    }

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

    private fun echoTool(
        name: String,
        requiredPermissions: List<String> = emptyList(),
        requiresAdmin: Boolean = false,
        handler: McpToolHandler = McpToolHandler { McpToolResult("ok:$name") },
    ) = McpToolDefinition(name = name, description = "test tool $name", handler = handler)
        .apply {
            this.requiredPermissions = requiredPermissions
            this.requiresAdmin = requiresAdmin
        }

    // ---------------------------------------------------------------------
    // permitted() semantics — admin bypass, requiresAdmin gate, containsAll
    // ---------------------------------------------------------------------

    @Test
    fun `tool with no requirements is exposed to a logged-out user`() {
        val core = McpToolRegistryCore(disabledFile = null)
        core.registerProvider(provider("p1", echoTool("open_tool")))

        // Default state before any updateAccess call: isAdmin=false, permissions=empty.
        assertTrue(core.tools.value.any { it.definition.name == "open_tool" })
    }

    @Test
    fun `tool requiring a permission is hidden until the user holds it`() {
        val core = McpToolRegistryCore(disabledFile = null)
        core.registerProvider(provider("p1", echoTool("gated_tool", requiredPermissions = listOf("secret.read"))))

        assertFalse(core.tools.value.any { it.definition.name == "gated_tool" })

        core.updateAccess(isAdmin = false, permissions = setOf("secret.read"))
        assertTrue(core.tools.value.any { it.definition.name == "gated_tool" })
    }

    @Test
    fun `tool requiring ALL permissions is hidden when only some are held`() {
        val core = McpToolRegistryCore(disabledFile = null)
        core.registerProvider(
            provider("p1", echoTool("multi_gate", requiredPermissions = listOf("role.read", "role.assign"))),
        )

        core.updateAccess(isAdmin = false, permissions = setOf("role.read"))
        assertFalse(core.tools.value.any { it.definition.name == "multi_gate" })

        core.updateAccess(isAdmin = false, permissions = setOf("role.read", "role.assign"))
        assertTrue(core.tools.value.any { it.definition.name == "multi_gate" })
    }

    @Test
    fun `admin bypasses requiredPermissions`() {
        val core = McpToolRegistryCore(disabledFile = null)
        core.registerProvider(provider("p1", echoTool("gated_tool", requiredPermissions = listOf("secret.read"))))

        core.updateAccess(isAdmin = true, permissions = emptySet())
        assertTrue(core.tools.value.any { it.definition.name == "gated_tool" })
    }

    @Test
    fun `admin bypasses requiresAdmin`() {
        val core = McpToolRegistryCore(disabledFile = null)
        core.registerProvider(provider("p1", echoTool("admin_tool", requiresAdmin = true)))

        core.updateAccess(isAdmin = true, permissions = emptySet())
        assertTrue(core.tools.value.any { it.definition.name == "admin_tool" })
    }

    @Test
    fun `non-admin with requiresAdmin is hidden even holding the listed permissions`() {
        val core = McpToolRegistryCore(disabledFile = null)
        core.registerProvider(
            provider("p1", echoTool("admin_only", requiredPermissions = listOf("role.read"), requiresAdmin = true)),
        )

        core.updateAccess(isAdmin = false, permissions = setOf("role.read"))
        assertFalse(core.tools.value.any { it.definition.name == "admin_only" })
    }

    @Test
    fun `revoking a permission live hides the tool without re-registration`() {
        val core = McpToolRegistryCore(disabledFile = null)
        core.registerProvider(provider("p1", echoTool("gated_tool", requiredPermissions = listOf("secret.read"))))
        core.updateAccess(isAdmin = false, permissions = setOf("secret.read"))
        assertTrue(core.tools.value.any { it.definition.name == "gated_tool" })

        core.updateAccess(isAdmin = false, permissions = emptySet())
        assertFalse(core.tools.value.any { it.definition.name == "gated_tool" })
    }

    @Test
    fun `allTools is not permission-filtered but tools is (metadata-only disclosure posture)`() {
        val core = McpToolRegistryCore(disabledFile = null)
        core.registerProvider(provider("p1", echoTool("admin_only", requiresAdmin = true)))

        // No admin, no permissions: the tool is invisible to invocation...
        assertFalse(core.tools.value.any { it.definition.name == "admin_only" })
        // ...but still listed in the full/management view.
        assertTrue(core.allTools.value.any { it.definition.name == "admin_only" })
    }

    // ---------------------------------------------------------------------
    // Dedup across providers
    // ---------------------------------------------------------------------

    @Test
    fun `duplicate tool name across providers - first registered wins`() {
        val core = McpToolRegistryCore(disabledFile = null)
        core.registerProvider(provider("first", echoTool("shared_name")))
        core.registerProvider(provider("second", echoTool("shared_name")))

        val matches = core.allTools.value.filter { it.definition.name == "shared_name" }
        assertEquals(1, matches.size, "duplicate tool name must be deduped, not both kept")
        assertEquals("first", matches.single().providerId)
    }

    @Test
    fun `unregistering the winning provider lets the second provider's tool take over`() {
        // recompute() flattens ALL *currently registered* providers on every
        // change (it is not "first-wins forever" — only "first-wins within one
        // pass"), so once "first" is gone, "second" (still registered) claims
        // "shared_name" on the very next recompute. Pin this down since it's easy
        // to assume permanent exclusion instead of a live re-flatten.
        val core = McpToolRegistryCore(disabledFile = null)
        core.registerProvider(provider("first", echoTool("shared_name")))
        core.registerProvider(provider("second", echoTool("shared_name")))
        core.unregisterProvider("first")

        val matches = core.allTools.value.filter { it.definition.name == "shared_name" }
        assertEquals(1, matches.size)
        assertEquals("second", matches.single().providerId)
    }

    @Test
    fun `a provider whose tools() throws registers with no tools and does not affect siblings`() {
        val core = McpToolRegistryCore(disabledFile = null)
        core.registerProvider(provider("healthy", echoTool("healthy_tool")))
        core.registerProvider(
            object : McpToolProvider {
                override val providerId = "broken"

                override fun tools(): List<McpToolDefinition> = error("plugin bug in tools()")
            },
        )
        core.registerProvider(provider("healthy2", echoTool("healthy_tool_2")))

        // The broken provider contributes nothing but doesn't take anyone down.
        assertTrue(core.allTools.value.any { it.definition.name == "healthy_tool" })
        assertTrue(core.allTools.value.any { it.definition.name == "healthy_tool_2" })
        assertTrue(core.allTools.value.none { it.providerId == "broken" })

        // And its teardown path stays consistent (id was tracked despite the throw).
        core.unregisterProvider("broken")
        assertTrue(core.allTools.value.any { it.definition.name == "healthy_tool" })
    }

    @Test
    fun `re-registering the same providerId replaces its tool set`() {
        val core = McpToolRegistryCore(disabledFile = null)
        core.registerProvider(provider("p1", echoTool("v1_tool")))
        assertTrue(core.allTools.value.any { it.definition.name == "v1_tool" })

        core.registerProvider(provider("p1", echoTool("v2_tool")))
        assertFalse(core.allTools.value.any { it.definition.name == "v1_tool" })
        assertTrue(core.allTools.value.any { it.definition.name == "v2_tool" })
    }

    // ---------------------------------------------------------------------
    // disabledToolNames persistence
    // ---------------------------------------------------------------------

    @Test
    fun `setToolEnabled false removes the tool from tools but not allTools`() {
        val core = McpToolRegistryCore(disabledFile = null)
        core.registerProvider(provider("p1", echoTool("toggle_me")))
        assertTrue(core.tools.value.any { it.definition.name == "toggle_me" })

        core.setToolEnabled("toggle_me", enabled = false)
        assertFalse(core.tools.value.any { it.definition.name == "toggle_me" })
        assertTrue(core.allTools.value.any { it.definition.name == "toggle_me" })
        assertTrue("toggle_me" in core.disabledToolNames.value)

        core.setToolEnabled("toggle_me", enabled = true)
        assertTrue(core.tools.value.any { it.definition.name == "toggle_me" })
    }

    @Test
    fun `disabled set persists across a fresh core instance reading the same file`() {
        val file = tempDisabledFile()
        val core1 = McpToolRegistryCore(disabledFile = file)
        core1.registerProvider(provider("p1", echoTool("persisted_tool")))
        core1.setToolEnabled("persisted_tool", enabled = false)
        assertTrue(file.exists(), "save should have written the file")

        val core2 = McpToolRegistryCore(disabledFile = file)
        assertTrue("persisted_tool" in core2.disabledToolNames.value)
    }

    @Test
    fun `save leaves no dangling tmp file (atomic rename completed)`() {
        val file = tempDisabledFile()
        val core = McpToolRegistryCore(disabledFile = file)
        core.registerProvider(provider("p1", echoTool("t")))
        core.setToolEnabled("t", enabled = false)

        val tmp = File(file.parentFile, file.name + ".tmp")
        assertFalse(tmp.exists(), "temp file must be renamed away, not left behind")
        assertTrue(file.exists())
    }

    @Test
    fun `corrupt disabled-tools file fails open to emptySet rather than crashing`() {
        val file = tempDisabledFile()
        file.parentFile.mkdirs()
        file.writeText("{ not valid json list ]")

        val core = McpToolRegistryCore(disabledFile = file)
        assertEquals(emptySet(), core.disabledToolNames.value)
    }

    @Test
    fun `missing disabled-tools file starts with an empty disabled set`() {
        val file = tempDisabledFile()
        assertFalse(file.exists())

        val core = McpToolRegistryCore(disabledFile = file)
        assertEquals(emptySet(), core.disabledToolNames.value)
    }

    @Test
    fun `null disabledFile skips persistence entirely (pure in-memory)`() {
        val core = McpToolRegistryCore(disabledFile = null)
        core.registerProvider(provider("p1", echoTool("t")))
        // Must not throw despite no file to write to.
        core.setToolEnabled("t", enabled = false)
        assertTrue("t" in core.disabledToolNames.value)
    }

    // ---------------------------------------------------------------------
    // invoke(): lookup, args parsing/coercion, timeout, cancellation
    // ---------------------------------------------------------------------

    @Test
    fun `invoke rejects unknown tool name`() =
        runBlocking {
            val core = McpToolRegistryCore(disabledFile = null)
            val result = core.invoke("does_not_exist", "{}")
            assertTrue(result.isError)
        }

    @Test
    fun `invoke rejects a disabled tool by name (unreachable even though still registered)`() =
        runBlocking {
            val core = McpToolRegistryCore(disabledFile = null)
            core.registerProvider(provider("p1", echoTool("disabled_tool")))
            core.setToolEnabled("disabled_tool", enabled = false)

            val result = core.invoke("disabled_tool", "{}")
            assertTrue(result.isError)
        }

    @Test
    fun `invoke rejects a permission-denied tool by name`() =
        runBlocking {
            val core = McpToolRegistryCore(disabledFile = null)
            core.registerProvider(provider("p1", echoTool("gated_tool", requiredPermissions = listOf("secret.read"))))
            // Never granted -> stays out of `tools`, so invoke can't reach it.

            val result = core.invoke("gated_tool", "{}")
            assertTrue(result.isError)
        }

    @Test
    fun `invoke parses string, boolean, integer, and double arguments`() =
        runBlocking {
            var captured: McpToolArgs? = null
            val core = McpToolRegistryCore(disabledFile = null)
            core.registerProvider(
                provider(
                    "p1",
                    echoTool(
                        "args_tool",
                        handler =
                            McpToolHandler { args ->
                                captured = args
                                McpToolResult("ok")
                            },
                    ),
                ),
            )

            core.invoke(
                "args_tool",
                """{"name":"hello","enabled":true,"count":42,"ratio":3.5,"missing_key_untouched":null}""",
            )

            val args = requireNotNull(captured)
            assertEquals("hello", args.string("name"))
            assertEquals(true, args.boolean("enabled"))
            assertEquals(42, args.int("count"))
            assertEquals(3.5, args.double("ratio"))
            assertTrue(args.has("missing_key_untouched"))
            assertNull(args.string("missing_key_untouched"))
            assertFalse(args.has("truly_absent_key"))
        }

    @Test
    fun `invoke coerces a JSON integer through int() and double()`() =
        runBlocking {
            // scalarOf() stores whole numbers as Long; McpToolArgs getters must still
            // hand back a usable Int/Double rather than silently going null.
            var captured: McpToolArgs? = null
            val core = McpToolRegistryCore(disabledFile = null)
            core.registerProvider(
                provider(
                    "p1",
                    echoTool(
                        "num_tool",
                        handler =
                            McpToolHandler { args ->
                                captured = args
                                McpToolResult("ok")
                            },
                    ),
                ),
            )

            core.invoke("num_tool", """{"n": 7}""")

            val args = requireNotNull(captured)
            assertEquals(7, args.int("n"))
            assertEquals(7.0, args.double("n"))
        }

    @Test
    fun `int() returns null for values outside Int range instead of silently wrapping`() =
        runBlocking {
            var captured: McpToolArgs? = null
            val core = McpToolRegistryCore(disabledFile = null)
            core.registerProvider(
                provider(
                    "p1",
                    echoTool(
                        "big_tool",
                        handler =
                            McpToolHandler { args ->
                                captured = args
                                McpToolResult("ok")
                            },
                    ),
                ),
            )

            core.invoke(
                "big_tool",
                """{"too_big": 9999999999, "too_small": -9999999999, "big_double": 1.0E12, "fits": 2147483647}""",
            )

            val args = requireNotNull(captured)
            assertNull(args.int("too_big"), "Long above Int.MAX_VALUE must not wrap")
            assertNull(args.int("too_small"), "Long below Int.MIN_VALUE must not wrap")
            assertNull(args.int("big_double"), "Double outside Int range must not saturate")
            assertEquals(Int.MAX_VALUE, args.int("fits"))
            // The full value stays reachable through the wider getters.
            assertEquals(9_999_999_999.0, args.double("too_big"))
        }

    @Test
    fun `invoke with malformed JSON args runs the handler with an empty arg set instead of erroring`() =
        runBlocking {
            var captured: McpToolArgs? = null
            val core = McpToolRegistryCore(disabledFile = null)
            core.registerProvider(
                provider(
                    "p1",
                    echoTool(
                        "bad_args_tool",
                        handler =
                            McpToolHandler { args ->
                                captured = args
                                McpToolResult("ok")
                            },
                    ),
                ),
            )

            val result = core.invoke("bad_args_tool", "{not valid json")

            assertFalse(result.isError)
            assertFalse(requireNotNull(captured).has("anything"))
        }

    @Test
    fun `invoke times out a handler that never completes`() =
        runBlocking {
            val core = McpToolRegistryCore(disabledFile = null, invokeTimeoutMs = 50L)
            core.registerProvider(
                provider(
                    "p1",
                    echoTool(
                        "hangs",
                        handler =
                            McpToolHandler {
                                delay(5_000)
                                McpToolResult("should never get here")
                            },
                    ),
                ),
            )

            val result = core.invoke("hangs", "{}")
            assertTrue(result.isError)
            assertTrue(result.text.contains("timed out", ignoreCase = true))
        }

    @Test
    fun `invoke wraps a handler exception into an error result`() =
        runBlocking {
            val core = McpToolRegistryCore(disabledFile = null)
            core.registerProvider(
                provider("p1", echoTool("throws", handler = McpToolHandler { error("boom") })),
            )

            val result = core.invoke("throws", "{}")
            assertTrue(result.isError)
            assertTrue(result.text.contains("boom"))
        }

    @Test
    fun `invoke propagates caller cancellation rather than swallowing it into an error result`() =
        runBlocking {
            val core = McpToolRegistryCore(disabledFile = null)
            core.registerProvider(
                provider(
                    "p1",
                    echoTool(
                        "slow",
                        handler =
                            McpToolHandler {
                                delay(5_000)
                                McpToolResult("unreachable")
                            },
                    ),
                ),
            )

            var threw: Throwable? = null
            try {
                coroutineScope {
                    val deferred = async { core.invoke("slow", "{}") }
                    delay(20)
                    deferred.cancel()
                    deferred.await()
                }
            } catch (t: Throwable) {
                threw = t
            }
            assertTrue(threw is CancellationException, "expected CancellationException, got $threw")
        }

    // ---------------------------------------------------------------------
    // Concurrency: the mutation lock must keep allTools/tools consistent
    // under mutators arriving from multiple threads (register/unregister/
    // setToolEnabled/updateAccess all race in production across dispatchers).
    // ---------------------------------------------------------------------

    @Test
    fun `concurrent register, unregister, and updateAccess never leave a stale or torn snapshot`() {
        val core = McpToolRegistryCore(disabledFile = null)
        val threads = mutableListOf<Thread>()
        val iterations = 200

        repeat(8) { workerIndex ->
            threads +=
                Thread {
                    repeat(iterations) { i ->
                        val id = "worker$workerIndex"
                        core.registerProvider(provider(id, echoTool("tool_${workerIndex}_$i")))
                        core.updateAccess(isAdmin = i % 2 == 0, permissions = setOf("p$i"))
                        core.unregisterProvider(id)
                    }
                }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        // After every worker has unregistered its last provider, nothing should
        // remain registered — if the mutation lock ever let a recompute overwrite
        // a later unregister with a stale snapshot, a provider's tools would
        // still be present here.
        assertTrue(core.allTools.value.isEmpty(), "no provider should remain registered: ${core.allTools.value}")
        assertTrue(core.tools.value.isEmpty())
    }
}
