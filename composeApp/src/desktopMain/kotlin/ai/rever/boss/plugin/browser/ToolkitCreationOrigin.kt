package ai.rever.boss.plugin.browser

import java.util.concurrent.atomic.AtomicReference

/** The first instrumented caller remains available even if startup entry points race. */
internal class ToolkitCreationOrigin {
    private val first = AtomicReference<String?>(null)
    val createdBy: String? get() = first.get()

    fun record(caller: String) {
        first.compareAndSet(null, caller)
    }
}
