package ai.rever.boss.services.supabase

import ai.rever.boss.utils.logging.ComponentLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray

internal data class DecodedSupabaseRows<T>(
    val values: List<T>,
    val droppedCount: Int,
) {
    val receivedCount: Int
        get() = values.size + droppedCount
}

/**
 * Decode a response array one row at a time, retaining valid rows when one record no longer
 * matches the installed client's schema. Parsing the array itself remains strict: truncated JSON
 * is a response failure, not a row that can safely be skipped.
 */
internal inline fun <reified T> decodeSupabaseRows(element: JsonElement): DecodedSupabaseRows<T> {
    val values = mutableListOf<T>()
    var droppedCount = 0
    element.jsonArray.forEach { row ->
        try {
            values += supabaseJson.decodeFromJsonElement<T>(row)
        } catch (_: SerializationException) {
            droppedCount++
        }
    }
    return DecodedSupabaseRows(values, droppedCount)
}

internal fun ComponentLogger.logDroppedSupabaseRows(
    category: LogCategory,
    message: String,
    operation: String,
    droppedCount: Int,
) {
    if (droppedCount == 0) return
    warn(
        category,
        message,
        data = mapOf("operation" to operation, "droppedRows" to droppedCount),
    )
}
