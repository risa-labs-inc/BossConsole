package ai.rever.boss.startup

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class StartupKernelOrderingTest {
    @Test
    fun `single instance forwarding exits before any kernel initialization`() {
        val root =
            generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
                .first { File(it, "settings.gradle.kts").isFile }
        val main = File(root, "composeApp/src/desktopMain/kotlin/ai/rever/boss/main.kt").readText()
        val plugin =
            File(root, "composeApp/src/commonMain/kotlin/ai/rever/boss/components/plugin/DefaultPlugin.kt").readText()
        assertTrue(!plugin.contains("System.getenv(\"BOSS_MODE\")"), "Plugin gates must use ConfigLoader precedence")
        val store =
            File(root, "composeApp/src/desktopMain/kotlin/ai/rever/boss/plugin/PluginStoreSetup.kt").readText()
        assertTrue(!store.contains("System.getenv(\"BOSS_MODE\")"), "Store fallback must use ConfigLoader precedence")
        val lock = main.indexOf("if (!SingleInstanceManager.acquireLock())")
        val kernel = main.indexOf("getMethod(\"initialize\")")
        assertTrue(lock >= 0 && kernel > lock)
        assertTrue(main.substring(lock, kernel).contains("exitProcess("))
    }
}
