package ai.rever.boss.plugin.sandbox.ui

/**
 * Desktop implementation: registers with [PluginCrashInterceptor] to catch
 * composition-time crashes via [Thread.UncaughtExceptionHandler].
 */
actual fun registerCrashInterceptor(pluginId: String, onError: (Throwable) -> Unit): (() -> Unit)? {
    val registration = PluginCrashInterceptor.register(pluginId, onError)
    return { registration.unregister() }
}

/**
 * Desktop implementation: installs the [PluginCrashInterceptor] into the
 * global [Thread.UncaughtExceptionHandler] chain.
 */
actual fun installCrashInterceptor() {
    PluginCrashInterceptor.install()
}
