package ai.rever.boss.components.home

import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal enum class HomeCatalogStatus { LOADING, READY, FAILED }

internal class HomeCatalogState {
    var status by mutableStateOf(HomeCatalogStatus.LOADING)
        internal set
    var rows by mutableStateOf<List<HomeStorePluginInput>>(emptyList())
        internal set
    var attempt by mutableStateOf(0)
        private set

    fun retry() {
        if (status != HomeCatalogStatus.FAILED) return
        status = HomeCatalogStatus.LOADING
        attempt++
    }
}

@Composable
internal fun rememberHomeCatalog(provider: HomeCatalogProvider?): HomeCatalogState {
    val state = remember(provider) { HomeCatalogState() }
    LaunchedEffect(provider, state.attempt) {
        if (provider == null) return@LaunchedEffect
        try {
            val rows = provider.discoverable()
            currentCoroutineContext().ensureActive()
            state.rows = rows
            state.status = HomeCatalogStatus.READY
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            state.status = HomeCatalogStatus.FAILED
        }
    }
    return state
}

@Composable
internal fun HomeCatalogNotice(state: HomeCatalogState) {
    when (state.status) {
        HomeCatalogStatus.LOADING -> {
            Text("Loading available tools…", color = BossTheme.colors.textMuted)
        }

        HomeCatalogStatus.FAILED -> {
            Column {
                Text(
                    "Couldn't load available tools. Installed tools are still available.",
                    color = BossTheme.colors.textSecondary,
                )
                TextButton(onClick = state::retry) { Text("Retry") }
            }
        }

        HomeCatalogStatus.READY -> {
            Unit
        }
    }
}
