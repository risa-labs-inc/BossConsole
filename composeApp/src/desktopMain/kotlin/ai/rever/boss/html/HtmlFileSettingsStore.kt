package ai.rever.boss.html

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/** Serializes initial loading and writes; tests use a temporary file, never the user's settings. */
internal class HtmlFileSettingsStore(
    private val file: File,
    private val onFailure: (Exception) -> Unit,
) {
    private val mutex = Mutex()
    private var loaded = false
    private val state = MutableStateFlow(HtmlFileSettings())
    val currentSettings = state.asStateFlow()
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    suspend fun awaitSettings(): HtmlFileSettings =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                loadLocked()
                state.value
            }
        }

    suspend fun update(settings: HtmlFileSettings? = null) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                loadLocked()
                if (settings != null) state.value = settings
                try {
                    file.parentFile?.mkdirs()
                    file.writeText(json.encodeToString(HtmlFileSettings.serializer(), state.value))
                } catch (e: IOException) {
                    onFailure(e)
                } catch (e: SecurityException) {
                    onFailure(e)
                }
            }
        }

    private fun loadLocked() {
        if (loaded) return
        try {
            if (file.exists()) state.value = json.decodeFromString<HtmlFileSettings>(file.readText())
        } catch (e: IOException) {
            onFailure(e)
        } catch (e: SecurityException) {
            onFailure(e)
        } catch (e: IllegalArgumentException) {
            onFailure(e)
        }
        loaded = true
    }
}
