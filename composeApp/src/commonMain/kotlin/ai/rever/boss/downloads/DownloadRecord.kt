package ai.rever.boss.downloads

import kotlinx.serialization.Serializable

/**
 * One completed download, recorded for the persistent history that survives restarts (unlike the
 * in-session `DownloadManager`, which tracks live progress in memory only).
 *
 * The file this is persisted in is hand-editable and migrated ahead of installed builds, so it is
 * read with `ignoreUnknownKeys = true` and every field except [id] carries a default.
 */
@Serializable
data class DownloadRecord(
    val id: String,
    val url: String = "",
    val fileName: String = "",
    val filePath: String = "",
    val sizeBytes: Long? = null,
    val completedAt: Long = 0L,
)

/** The on-disk document: the whole history under one key, mirroring the other state stores. */
@Serializable
data class DownloadHistory(
    val downloads: List<DownloadRecord> = emptyList(),
)
