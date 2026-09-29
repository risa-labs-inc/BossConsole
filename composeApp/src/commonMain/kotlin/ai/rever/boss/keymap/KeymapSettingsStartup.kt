package ai.rever.boss.keymap

/** Touching the receiver completes its synchronous startup load before a recovery is claimed. */
internal fun KeymapSettingsManager.ensureLoaded() {
    // Accessing the object receiver already ran its initializer; do not reload settings here.
}
