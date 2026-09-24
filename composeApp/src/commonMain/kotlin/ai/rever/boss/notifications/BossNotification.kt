package ai.rever.boss.notifications

import kotlinx.serialization.Serializable

/** Severity of a [BossNotification], used for grouping and (later) colour in the UI. */
enum class NotificationLevel {
    INFO,
    SUCCESS,
    WARNING,
    ERROR,
    ;

    companion object {
        /** Parse a caller-supplied level, case-insensitively, falling back to [INFO]. */
        fun fromString(raw: String?): NotificationLevel {
            val key = raw?.trim()
            return entries.firstOrNull { it.name.equals(key, ignoreCase = true) } ?: INFO
        }
    }
}

/**
 * One entry in the operator's notification inbox: a durable message from the host or an agent
 * (a long task finished, an update is available, an agent left a note), as opposed to the
 * transient status line that the next message cancels.
 *
 * The file this is persisted in is hand-editable and is migrated ahead of installed builds the
 * same way every other BOSS state file is, so it is read with `ignoreUnknownKeys = true` and
 * every field except [id] carries a default.
 */
@Serializable
data class BossNotification(
    val id: String,
    val title: String = "",
    val message: String = "",
    val level: NotificationLevel = NotificationLevel.INFO,
    val source: String = "",
    val createdAt: Long = 0L,
    val read: Boolean = false,
)

/**
 * The on-disk document: the whole inbox under one key, mirroring the other state stores. A
 * wrapper object rather than a bare list so a later field can be added without rewriting every
 * existing file.
 */
@Serializable
data class NotificationStore(
    val notifications: List<BossNotification> = emptyList(),
)
