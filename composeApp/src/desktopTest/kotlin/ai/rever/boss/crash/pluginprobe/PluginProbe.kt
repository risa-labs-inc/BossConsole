package ai.rever.boss.crash.pluginprobe

/**
 * Test fixture for [ai.rever.boss.crash.CrashHandlerAttributionTest].
 *
 * These classes are compiled onto the desktopTest classpath, but the test
 * repackages their .class bytes into a standalone jar and loads them through a
 * real PluginClassLoader — producing throwables whose stack frames (and, for
 * [ProbeException], whose class itself) are DEFINED by a plugin classloader,
 * exactly like a crash inside a dynamically loaded plugin.
 */
class PluginProbe {
    fun boom() {
        throw IllegalStateException("probe boom")
    }

    fun boomCustom() {
        throw ProbeException()
    }
}

class ProbeException : RuntimeException("custom probe exception")
