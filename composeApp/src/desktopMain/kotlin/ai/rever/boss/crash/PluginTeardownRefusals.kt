package ai.rever.boss.crash

import ai.rever.boss.plugin.loader.PluginClassLoader
import ai.rever.boss.plugin.loader.PluginUnloadRefusal

/**
 * Recognises a plugin classloader's teardown refusal wherever it surfaces, for [CrashHandler]:
 * absorbed as benign (no dialog, no exit), yet still recorded as a contained report.
 */
internal object PluginTeardownRefusals {
    /**
     * A plugin classloader refusing a class request once its plugin has begun unloading
     * (UNLOAD_IN_PROGRESS) or has unloaded (UNLOADED).
     *
     * `PluginClassLoader` answers a late request with a [PluginUnloadRefusal] (a
     * `ClassNotFoundException`) **on purpose** - delegating to the host would splice two class
     * graphs together. When the request is the JVM resolving a symbolic reference in a plugin
     * class (a `new`, a field or method reference its code reaches only now), resolution fails
     * with a `NoClassDefFoundError` at that site, on whatever straggler thread made the request:
     * a Ktor selector actor, a coroutine dispatcher, an AWT handler. The JVM keeps the loader's
     * exception as that error's cause, which is how [CrashHandler.isIgnorable]'s walk over the cause chain
     * reaches the refusal; `PluginTeardownRefusalIntegrationTest` drives exactly that through a
     * real loader and a real unload. Nothing is broken at that point; the refusal is the
     * protection working, and the loader has already logged it at WARN with the straggler's stack.
     *
     * What followed was not benign: the refusal reached the uncaught handler during an ordinary
     * unload - a plugin update, a reload, a sign-out - and was classified as a crash. Recovery
     * for an already-unloaded plugin is unavailable, so the crash classification resolves it to
     * [CrashDisposition.FatalHost] and the user gets the crash dialog, whose dismissal exits
     * BOSS. Filed three times from two different plugins and both platforms:
     * risa-labs-inc/boss-plugin-terminal-tab#69 (terminaltab, macOS), #71 (fluckbrowser, then
     * terminaltab - "On sign out get this"), #76 (terminaltab, Windows).
     *
     * Matched by the loader's own refusal type, not by its message or by `NoClassDefFoundError`
     * in general. The loader and this handler ship in the same build, so the type cannot drift
     * the way the sentence did (its dash changed between 9.4.0 and 9.4.13). And a class
     * genuinely missing from a live plugin's jar arrives as a plain `ClassNotFoundException`, so
     * it is still reported. The straggler reference remains the bug; this only stops the
     * teardown artifact from being shown to the user as a crash. It is not silenced: both
     * `CrashHandler.handleCrash` and [CrashHandler.recordContained] still write a deduplicated contained report for it,
     * because that report is where the straggler's stack reaches whoever fixes the reference.
     *
     * The chain walk follows `cause` only, bounded as [CrashHandler.isIgnorable]'s is; a refusal attached as a
     * suppressed exception (a `use {}` whose close failed during teardown) is not matched.
     */
    fun matches(throwable: Throwable): Boolean {
        if (throwable is PluginUnloadRefusal) return true
        return isResolutionFailureInRetiredPlugin(throwable)
    }

    /**
     * The second and every later failure at the same call site. The JVM caches a failed
     * resolution per call site and rethrows it on each later attempt, but it keeps only the
     * cause's class NAME and rebuilds it through the boot loader, which cannot see
     * [PluginUnloadRefusal] - so from the second hit on, the cause is a `NoClassDefFoundError`
     * naming that class, not the refusal. `PluginTeardownRefusalIntegrationTest` pins exactly that.
     *
     * Two structural facts identify it without reading a message:
     * - **The cause is not a `ClassNotFoundException`.** A live plugin's missing class fails with a
     *   real `ClassNotFoundException` as the cause on every hit, first or later, because the JVM can
     *   rebuild that class by name. Only a cause it could not rebuild - or none - gets past this.
     * - **The class the error was thrown in belongs to a plugin loader that has left ACTIVE.** The
     *   error's top frame is the call site; [PluginClassLoader.isDefinedByRetiredLoader] answers
     *   for its class.
     *
     * An update's old and new loaders define classes of the same names, so the second fact alone
     * cannot tell them apart; the first is what keeps the new version's genuine misses reported.
     */
    private fun isResolutionFailureInRetiredPlugin(throwable: Throwable): Boolean =
        throwable is NoClassDefFoundError &&
            throwable.cause !is ClassNotFoundException &&
            throwable.stackTrace
                .firstOrNull()
                ?.className
                ?.let(PluginClassLoader::isDefinedByRetiredLoader) == true

    /** Whether [throwable]'s cause chain holds a plugin teardown refusal (see [matches]). */
    fun inChain(throwable: Throwable): Boolean = throwable.chainOfCauses().any(::matches)
}
