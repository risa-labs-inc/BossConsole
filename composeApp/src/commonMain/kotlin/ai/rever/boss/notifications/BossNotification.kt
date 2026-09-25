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
 * Who a notification provably came from, stamped by [NotificationCenter] at the post boundary.
 *
 * A notification's `source` string is caller-supplied text, and one caller is an AI agent reaching
 * the inbox through the MCP tools (`NotificationMcpToolProvider`). Before this type existed the
 * string was the only origin signal, so an agent that passed `source = "System"` was
 * indistinguishable in the inbox from a genuine host notice (BossConsole#1587). [origin] is the
 * authoritative answer instead: the host classifies its own posts as [HOST], agent-reachable
 * surfaces classify theirs as [AGENT], and the centre refuses to let [AGENT] posts carry a bare
 * source label - it demotes the caller's label to a display string prefixed with `agent`
 * (see [NotificationCenter.post]).
 *
 * [AGENT] is the serialized default on purpose: the inbox file is hand-editable and predates this
 * field, and an entry that cannot prove host provenance must not present as a host notice. Failing
 * closed here means an unknown or absent origin reads as agent-side, never as system origin.
 */
@Serializable
enum class NotificationOrigin {
    /** Posted by the host itself. Only host code may claim this; the host trusts its own label. */
    HOST,

    /** Posted by an agent through an agent-reachable surface (the MCP notification tools). */
    AGENT,
}

/**
 * One entry in the operator's notification inbox: a durable message from the host or an agent
 * (a long task finished, an update is available, an agent left a note), as opposed to the
 * transient status line that the next message cancels.
 *
 * The file this is persisted in is hand-editable and is migrated ahead of installed builds the
 * same way every other BOSS state file is, so it is read with `ignoreUnknownKeys = true` plus
 * `coerceInputValues = true`, and every field except [id] carries a default an unknown value
 * can fall back to. [origin] defaults to [NotificationOrigin.AGENT]: missing or unrecognized
 * provenance fails closed and cannot present as a host notice.
 */
@Serializable
data class BossNotification(
    val id: String,
    val title: String = "",
    val message: String = "",
    val level: NotificationLevel = NotificationLevel.INFO,
    val source: String = "",
    val origin: NotificationOrigin = NotificationOrigin.AGENT,
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
