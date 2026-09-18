package ai.rever.boss.crash

import ai.rever.boss.plugin.sandbox.ui.PluginRenderRecovery
import ai.rever.boss.plugin.sandbox.ui.isUncontainable

/**
 * What the window exception handler should do with a throwable that escaped the
 * Compose render loop.
 *
 * Split out of `main.kt` so the decision can be tested. It used to live inline in
 * an anonymous `WindowExceptionHandler` inside `application {}`, where the one
 * thing worth asserting — that a dead JVM is never "contained" — could only be
 * argued for.
 */
enum class WindowExceptionRoute {
    /** Attributable to a plugin; the crash interceptor owns it. */
    PluginHandled,

    /**
     * Blamed on a plugin that has no boundary to hand it to. Quarantine that
     * plugin and keep the app.
     */
    QuarantinePlugin,

    /** Contain: keep the window, recover the plugin panels, tell the user. */
    Contain,

    /** Hand to Compose's default handler, which disposes the window and ends the app. */
    Escalate,
}

/**
 * Decide how to route [throwable].
 *
 * Order matters and is the point of this function:
 *
 * 1. **Attributed** to a plugin — the interceptor already knows what to do.
 * 2. **Uncontainable** ([hasUncontainableCause], the whole cause chain and not
 *    just the top - a wrapped OutOfMemoryError is still an OutOfMemoryError, and
 *    this check was flat while its twin in [classifyCrash] was not) — escalate
 *    before anything else. The
 *    render boundary rethrows [OutOfMemoryError] rather than blaming a plugin for
 *    it, and that carve-out is worthless unless this agrees: containing here
 *    would log, toast, and repaint every window, which under heap exhaustion is
 *    more allocation, three times, before the circuit breaker gives up.
 * 3. **Blamed on a plugin** ([blamedPluginId]) — quarantine it and keep the app.
 *    This sits *below* the fatal check and *above* the uncontainable one, and
 *    that placement is the whole point of the branch.
 *
 *    A `StackOverflowError` is uncontainable in the sense the boundary means:
 *    you cannot recover by repainting, because the repaint re-enters whatever
 *    recursed. But the stack has unwound by the time we get here — the *process*
 *    is fine — and if we know which plugin did it we can remove that plugin
 *    instead of repainting everything. Escalating was throwing away a session to
 *    avoid a redraw we were not going to attempt anyway.
 *
 *    This is not hypothetical: `TerminalTabPluginAPIImpl.setPendingSidebarCommand`
 *    recursed into itself, and BOSS exited. Every frame named the plugin; it just
 *    had no boundary mounted, so [attributedPluginId] was null and nothing below
 *    looked at the blame again.
 * 4. **Too many failures too fast** — the scene is not recovering, so stop
 *    pretending. Note this consumes a slot in [policy], so it must be reached
 *    only for faults actually eligible for containment; that is why the
 *    uncontainable check sits above it rather than below.
 * 5. Otherwise contain.
 */
fun decideWindowExceptionRoute(
    throwable: Throwable,
    attributedPluginId: String?,
    policy: RenderCrashPolicy,
    blamedPluginId: String? = null,
): WindowExceptionRoute =
    when {
        attributedPluginId != null -> WindowExceptionRoute.PluginHandled
        throwable.hasFatalCause() -> WindowExceptionRoute.Escalate
        blamedPluginId != null -> WindowExceptionRoute.QuarantinePlugin
        throwable.hasUncontainableCause() -> WindowExceptionRoute.Escalate
        !policy.recordFailureAndShouldContain() -> WindowExceptionRoute.Escalate
        else -> WindowExceptionRoute.Contain
    }

/**
 * Tell [policy] whether the fault it just recorded was one recovery could act on.
 *
 * The pairing lives here rather than inline in the handler so that production and
 * tests exercise the *same* decision. They did not: the seam test re-implemented
 * this `Rebuilt || Quarantined` condition, so deleting the call from the handler
 * left every test green — the wiring the test was named for was never asserted.
 *
 * [PluginRenderRecovery.Outcome.Rebuilt] and
 * [PluginRenderRecovery.Outcome.Quarantined] advance the narrowing loop, so their
 * faults are refunded and the caller should repaint. [PluginRenderRecovery.Outcome.Settling]
 * means no visible state changed. Its fault is refunded only while [policy]'s
 * burst-wide settle deadline remains open, and it must not trigger a repaint.
 * [PluginRenderRecovery.Outcome.Unexplained] and
 * [PluginRenderRecovery.Outcome.NotPluginRelated] mean it did not, and those must
 * keep accumulating or a corrupt scene never escalates.
 *
 * @return true only when recovery changed visible state and the caller should
 *   repaint. Refund and repaint are deliberately separate decisions.
 */
internal fun noteRecoveryOutcome(
    policy: RenderCrashPolicy,
    outcome: PluginRenderRecovery.Outcome,
): Boolean =
    when (outcome) {
        is PluginRenderRecovery.Outcome.Rebuilt,
        is PluginRenderRecovery.Outcome.Quarantined,
        -> {
            policy.noteRecoveryProgress()
            true
        }

        is PluginRenderRecovery.Outcome.Settling -> {
            policy.noteSettlingFault()
            false
        }

        PluginRenderRecovery.Outcome.Unexplained,
        PluginRenderRecovery.Outcome.NotPluginRelated,
        -> {
            false
        }
    }
