package ai.rever.boss.updater

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Accepted preference changes outlive the Settings window that requested them. */
internal class UpdatePreferenceWriter(
    private val scope: CoroutineScope,
    private val save: suspend () -> Unit,
) {
    fun requestSave() = scope.launch { save() }

    companion object {
        val instance =
            UpdatePreferenceWriter(CoroutineScope(SupervisorJob() + Dispatchers.Default)) {
                UpdateSettingsManager.saveSettings()
            }
    }
}
