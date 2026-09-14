package ai.rever.boss.components.plugin

/**
 * Check a prompt without removing it during suspending work, then claim it even when it is
 * already installed or declined. The caller must publish a returned prompt without suspending.
 */
internal suspend fun PluginDependencyBus.claimMissingDependencyForWindow(
    prompt: MissingDependencyPrompt,
    collectorWindowId: String,
    targetWindowOpen: Boolean,
    isPresent: suspend () -> Boolean,
): MissingDependencyPrompt? {
    if (!shouldClaimMissingDependencyPrompt(prompt, collectorWindowId, targetWindowOpen)) return null
    val present = isPresent()
    val show = shouldShowMissingDependency(prompt, present, wasDeclined(prompt.missing))
    // Suppressed entries must also release their keys; otherwise a later explicit retry is
    // rejected forever. Cancellation during isPresent leaves the entry available to another window.
    return if (claim(prompt) && show) prompt else null
}
