package ai.rever.boss.updater

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer

/** Invalid catalog hints must not prevent decoding otherwise usable releases. */
internal object MinimumOsSerializer : JsonTransformingSerializer<Map<String, String>>(
    MapSerializer(String.serializer(), String.serializer()),
) {
    override fun transformDeserialize(element: JsonElement): JsonElement =
        JsonObject(
            (element as? JsonObject)?.filterValues { value ->
                value is JsonPrimitive && value.isString
            } ?: emptyMap(),
        )
}
