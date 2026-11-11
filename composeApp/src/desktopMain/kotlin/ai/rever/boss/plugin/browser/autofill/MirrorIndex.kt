package ai.rever.boss.plugin.browser.autofill

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Keys of the Chromium records the mirror added, per profile path. Persisted so a crash before
 * clear-on-quit is cleaned up on the next sync. Keys only: no values.
 */
internal class MirrorIndex(
    private val file: File,
) {
    private val serializer = MapSerializer(String.serializer(), ListSerializer(String.serializer()))
    private val byProfile: MutableMap<String, MutableSet<String>> by lazy { load() }

    private fun load(): MutableMap<String, MutableSet<String>> =
        runCatching {
            if (!file.exists()) return@runCatching mutableMapOf()
            Json.decodeFromString(serializer, file.readText()).mapValuesTo(mutableMapOf()) { it.value.toMutableSet() }
        }.getOrElse { mutableMapOf() }

    @Synchronized
    fun keys(profile: String): Set<String> = byProfile[profile]?.toSet().orEmpty()

    @Synchronized
    fun add(
        profile: String,
        key: String,
    ) {
        if (profile.isNotEmpty()) byProfile.getOrPut(profile) { mutableSetOf() } += key
    }

    @Synchronized
    fun remove(
        profile: String,
        key: String,
    ) {
        byProfile[profile]?.remove(key)
    }

    @Synchronized
    fun clear(profile: String) {
        byProfile.remove(profile)
    }

    @Synchronized
    fun save() {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(Json.encodeToString(serializer, byProfile.filterValues { it.isNotEmpty() }.mapValues { it.value.sorted() }))
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
