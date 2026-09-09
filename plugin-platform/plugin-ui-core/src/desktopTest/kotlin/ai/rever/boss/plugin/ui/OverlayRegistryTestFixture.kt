package ai.rever.boss.plugin.ui

/** Clears one startup-only backing field so independent test fixtures can inject their own host. */
internal fun resetOverlayFieldForTest(name: String) {
    val type = ai.rever.boss.plugin.ui.BossOverlayHost::class.java
    val field = type.getDeclaredField(if (name == "useHeavyweightOverlays") "useHeavyweightOverlaysWritten" else name)
    field.isAccessible = true
    field.set(null, if (name == "useHeavyweightOverlays") false else null)
}
