package ai.rever.boss.pet

import ai.rever.boss.config.BossPetSettingsManager
import ai.rever.boss.config.parseBossPetEnabled
import ai.rever.boss.updater.UpdateManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/** Isolates pet state reads from the application scope that hosts the main windows. */
@Composable
fun BossPetHost() {
    val enabledFlow =
        remember {
            flow {
                emitAll(
                    BossPetSettingsManager.settings.map {
                        parseBossPetEnabled(BossPetSettingsManager.envOverride()) ?: it.enabled
                    },
                )
            }.flowOn(Dispatchers.IO).distinctUntilChanged()
        }
    val enabled by enabledFlow.collectAsState(initial = false)
    var hidden by remember(enabled) { mutableStateOf(false) }
    BossPetNoticeTimer()
    BossPetUpdateBridge()
    if (enabled && !hidden) {
        BossPetWindow(onHide = { hidden = true })
    }
}

/** Notice lifetime is independent of the selected producers and window visibility. */
@Composable
private fun BossPetNoticeTimer() {
    val mood =
        BossPet.controller.mood
            .collectAsState()
            .value
    LaunchedEffect(mood) {
        if (mood is BossPetMood.Completed && !mood.requiresAcknowledgement) {
            delay(6_000)
            BossPet.controller.onIdleTimeout(mood)
        }
    }
}

/** Read-only updater observation remains mounted when the pet is hidden. */
@Composable
private fun BossPetUpdateBridge() {
    LaunchedEffect(Unit) {
        UpdateManager.instance.updateState.collect { reportPetUpdateState(BossPet.controller, it) }
    }
}
