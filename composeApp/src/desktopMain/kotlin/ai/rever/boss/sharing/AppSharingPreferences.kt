package ai.rever.boss.sharing

import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.utils.atomicWriteText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
internal data class AppSharingLocalPreferences(
    val relayEnabled: Boolean = true,
    val deviceId: String = UUID.randomUUID().toString(),
    val automaticSharingEnabled: Boolean = true,
)

/** Loading only reads policy. The host coordinator checks account and window ownership before capture. */
internal class AppSharingPreferenceStore(
    private val file: File = BossDirectories.resolve("app-sharing.json"),
) {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    fun load(): AppSharingLocalPreferences =
        runCatching {
            if (file.exists()) {
                json.decodeFromString(AppSharingLocalPreferences.serializer(), file.readText())
            } else {
                AppSharingLocalPreferences()
            }
        }.getOrDefault(AppSharingLocalPreferences(automaticSharingEnabled = false))

    fun save(preferences: AppSharingLocalPreferences) {
        require(UUID.fromString(preferences.deviceId).toString() == preferences.deviceId)
        file.parentFile?.mkdirs()
        file.atomicWriteText(json.encodeToString(AppSharingLocalPreferences.serializer(), preferences))
    }
}
