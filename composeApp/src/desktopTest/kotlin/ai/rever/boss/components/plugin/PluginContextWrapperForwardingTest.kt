package ai.rever.boss.components.plugin

import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.sandbox.context.SandboxedPluginContext
import kotlin.reflect.KClass
import kotlin.reflect.full.declaredMembers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every [PluginContext] member must be declared by both wrappers a dynamic plugin is given.
 *
 * Almost every member has a default implementation that returns null or does nothing, so a member a
 * wrapper does not override compiles, loads and fails silently for every plugin. AGENTS.md records
 * this for `mcpToolRegistry`; `registerSearchProvider` was the same gap in both wrappers.
 *
 * Kotlin reflection, not Java reflection: the api is compiled with the default `-jvm-default=enable`,
 * which puts a compatibility bridge for each default method into every implementing class, so
 * `Class.declaredMethods` lists members a wrapper never overrode. Kotlin metadata lists only what the
 * source declares.
 *
 * The expected set is every member of [PluginContext] including those it inherits, less [Any]'s, so
 * splitting the api interface into a base and extensions cannot quietly narrow the check.
 *
 * What it cannot see: members are matched by name, so an override that forwards to the wrong
 * delegate, or drops the call, still passes. A new `PluginContext` wrapper has to be added here. An
 * api version that adds a member fails both tests until the wrappers declare it, and the failure
 * names the member.
 */
class PluginContextWrapperForwardingTest {
    private fun missingFrom(
        wrapper: KClass<*>,
        api: KClass<*> = PluginContext::class,
    ): Set<String> {
        val expected = api.members.map { it.name }.toSet() - Any::class.members.map { it.name }.toSet()
        return expected - wrapper.declaredMembers.map { it.name }.toSet()
    }

    @Test
    fun `TrackingPluginContext declares every PluginContext member`() {
        val missing = missingFrom(TrackingPluginContext::class)
        assertTrue(missing.isEmpty(), "TrackingPluginContext falls through to a PluginContext default for: $missing")
    }

    @Test
    fun `SandboxedPluginContext declares every PluginContext member`() {
        val missing = missingFrom(SandboxedPluginContext::class)
        assertTrue(missing.isEmpty(), "SandboxedPluginContext falls through to a PluginContext default for: $missing")
    }

    @Test
    fun `a member inherited from a supertype of the api interface is still expected`() {
        assertEquals(setOf("inherited"), missingFrom(LeafOnlyWrapper::class, api = LeafContext::class))
    }

    private interface BaseContext {
        fun inherited() {}
    }

    private interface LeafContext : BaseContext {
        fun declared() {}
    }

    private class LeafOnlyWrapper : LeafContext {
        override fun declared() = Unit
    }
}
