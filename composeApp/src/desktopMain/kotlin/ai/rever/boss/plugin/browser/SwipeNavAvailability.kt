package ai.rever.boss.plugin.browser

import ai.rever.boss.config.SwipeNavSettingsManager
import ai.rever.boss.config.parseSwipeNavEnabled

/** Capability reporting must not mistake a run-loop failure for denied Input Monitoring. */
internal fun swipeNavSettingsDescription(
    envOverride: String?,
    availability: ScrollPhaseAvailability,
): String {
    val envOwned = parseSwipeNavEnabled(envOverride) != null
    val retry = if (envOwned) "Restart BOSS to retry." else "Turn swipe navigation off and on to retry."
    val capability =
        when (availability) {
            ScrollPhaseAvailability.PERMISSION_DENIED -> {
                "Allow BOSS under System Settings > Privacy & Security > Input Monitoring. " +
                    "If BOSS is absent, use + and select BOSS.app. $retry"
            }

            ScrollPhaseAvailability.FAILED -> {
                "Release detection stopped. $retry"
            }

            ScrollPhaseAvailability.STARTING -> {
                "Starting trackpad release detection."
            }

            else -> {
                "Swipe right with two fingers to go back, left to go forward."
            }
        }
    return if (envOwned) {
        "Set by ${SwipeNavSettingsManager.KEY}=$envOverride in the environment. $capability"
    } else {
        capability
    }
}
