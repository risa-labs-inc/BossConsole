package ai.rever.boss.plugin.sandbox.ui

/**
 * Platform-specific crash interceptor registration.
 *
 * On desktop, this hooks into [Thread.UncaughtExceptionHandler] via [PluginCrashInterceptor]
 * to catch composition-time crashes (e.g., `NoSuchMethodError` from binary incompatibility)
 * and attribute them to the offending plugin.
 *
 * @param pluginId The plugin ID to register for
 * @param onError Callback when a crash is attributed to this plugin
 * @return A dispose function to unregister, or null if not supported on this platform
 */
expect fun registerCrashInterceptor(pluginId: String, onError: (Throwable) -> Unit): (() -> Unit)?

/**
 * Install the platform-specific crash interceptor.
 *
 * On desktop, this sets up the [Thread.UncaughtExceptionHandler] chain.
 * Should be called once during app startup, after the global crash handler is installed.
 */
expect fun installCrashInterceptor()
