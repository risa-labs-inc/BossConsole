package ai.rever.boss.kernel

import ai.rever.boss.process.ProcessSpawner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The reflective contract `DefaultPlugin` depends on to wire out-of-process plugins.
 *
 * `DefaultPlugin.dynamicPluginManager` reaches the kernel entirely through `Class.forName` and
 * `getConstructor`/`getMethod`, because `composeApp`'s common source set cannot depend on the
 * desktop-only process manager. Reflection means the compiler checks none of it: renaming a class,
 * adding a constructor parameter, or changing a getter leaves every other test green while the host
 * silently falls back to in-process plugins at runtime, logged once as
 * `OOP spawner: unexpected error` and swallowed.
 *
 * That is not hypothetical. Adding a third parameter to [ProcessSpawner] removed the
 * `(String, File)` constructor that `DefaultPlugin` looked up - Kotlin emits only the full-arity
 * constructor plus a synthetic defaults bridge, with no `@JvmOverloads` - so out-of-process plugins
 * would have stopped spawning entirely, in the same change that set out to stop them leaking.
 *
 * Tests mirror reflective lookups in `DefaultPlugin` and `PerformanceDataProviderImpl`. Keep them in step
 * with those call sites: if a lookup here needs updating, the runtime behaviour changed.
 */
class KernelReflectionContractTest {
    @Test
    fun `KernelBootstrap Companion getInstance resolves`() {
        val bootstrapCls = Class.forName("ai.rever.boss.kernel.KernelBootstrap")
        val companionCls = Class.forName("ai.rever.boss.kernel.KernelBootstrap\$Companion")

        // DefaultPlugin reads the Companion off the static field, then calls getInstance() on it.
        val companionField = bootstrapCls.getDeclaredField("Companion")
        assertTrue(companionField.get(null) != null, "KernelBootstrap.Companion must be reachable")
        companionCls.getMethod("getInstance")
    }

    @Test
    fun `KernelBootstrap getProcessSpawner resolves and returns a ProcessSpawner`() {
        val bootstrapCls = Class.forName("ai.rever.boss.kernel.KernelBootstrap")

        // The plugin side reuses this spawner rather than building its own, because only this one
        // is wired to the registry the shutdown hook reaps.
        val getter = bootstrapCls.getMethod("getProcessSpawner")

        assertEquals(
            ProcessSpawner::class.java,
            getter.returnType,
            "DefaultPlugin passes this straight into OutOfProcessPluginSpawnerImpl's constructor",
        )
    }

    @Test
    fun `OutOfProcessPluginSpawnerImpl constructor resolves`() {
        val spawnerCls = Class.forName("ai.rever.boss.components.plugin.OutOfProcessPluginSpawnerImpl")
        val processSpawnerCls = Class.forName("ai.rever.boss.process.ProcessSpawner")

        // Exactly DefaultPlugin's lookup. A defaulted Kotlin parameter added here would NOT keep
        // this three-arg form alive without @JvmOverloads.
        spawnerCls.getConstructor(
            processSpawnerCls,
            String::class.java,
            String::class.java,
        )
    }

    @Test
    fun `ProcessSpawner is constructible with the registry it must carry`() {
        // Not a DefaultPlugin lookup any more - it reuses the kernel's instance - but the kernel
        // itself builds this, and a spawner without a registry registers nothing.
        val ctor =
            ProcessSpawner::class.java.getConstructor(
                String::class.java,
                java.io.File::class.java,
                Class.forName("ai.rever.boss.process.ProcessRegistry"),
            )
        assertEquals(3, ctor.parameterCount)
    }

    @Test
    fun `performance configuration metadata getters retain their reflective contract`() {
        val config = Class.forName("ai.rever.boss.process.ProcessConfig")
        assertEquals(String::class.java, config.getMethod("getProcessId").returnType)
        assertEquals(String::class.java, config.getMethod("getDisplayName").returnType)
        assertEquals(Map::class.java, config.getMethod("getEnvironment").returnType)
    }

    @Test
    fun `performance managed process getters retain their reflective contract`() {
        val process = Class.forName("ai.rever.boss.process.ManagedProcess")
        assertEquals(Class.forName("ai.rever.boss.process.ProcessConfig"), process.getMethod("getConfig").returnType)
        assertEquals(Long::class.javaPrimitiveType, process.getMethod("getPid").returnType)
        assertEquals(Boolean::class.javaPrimitiveType, process.getMethod("isAlive").returnType)
    }
}
