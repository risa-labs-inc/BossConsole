package ai.rever.boss.crash

/**
 * AWT can deliver a queued resize after ComposeWindowPanel has been disposed.
 * The October 31 contained report starts with this exact getter/setBounds pair.
 * No live composition failed: rebuilding plugin panels cannot repair a disposed
 * panel, and repeated callbacks must not exhaust the render recovery budget.
 */
internal fun isDisposedComposeWindowResize(throwable: Throwable): Boolean {
    if (throwable.hasFatalCause()) return false
    return throwable.chainOfCauses().any { cause ->
        val frames = cause.stackTrace
        cause is IllegalArgumentException &&
            cause.message == "ComposeContainer is disposed" &&
            frames.getOrNull(0)?.let {
                it.className == "androidx.compose.ui.awt.ComposeWindowPanel" && it.methodName == "getComposeContainer"
            } == true &&
            frames.getOrNull(1)?.let {
                it.className == "androidx.compose.ui.awt.ComposeWindowPanel" && it.methodName == "setBounds"
            } == true
    }
}
