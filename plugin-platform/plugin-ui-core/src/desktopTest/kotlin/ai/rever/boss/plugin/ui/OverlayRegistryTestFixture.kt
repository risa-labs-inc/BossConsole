package ai.rever.boss.plugin.ui

/**
 * Clears a startup field and its registration marker for independent test hosts.
 * Keep the composeApp and plugin-ui-core desktopTest copies aligned; neither is production API.
 */
internal fun resetOverlayFieldForTest(name: String) {
    val type = ai.rever.boss.plugin.ui.BossOverlayHost::class.java
    val field = type.getDeclaredField(if (name == "useHeavyweightOverlays") "useHeavyweightOverlaysWritten" else name)
    field.isAccessible = true
    field.set(null, if (name == "useHeavyweightOverlays") false else null)
    if (name == "useHeavyweightOverlays") {
        type.getDeclaredField(name).apply {
            isAccessible = true
            setBoolean(null, false)
        }
    }
}
