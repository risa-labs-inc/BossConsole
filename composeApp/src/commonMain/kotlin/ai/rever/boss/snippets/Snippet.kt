package ai.rever.boss.snippets

import kotlinx.serialization.Serializable

/**
 * A reusable prompt or command snippet the operator (or an agent through the MCP tools) can
 * store once and recall by id or tag.
 *
 * The file this is persisted in is hand-editable and is migrated ahead of installed builds the
 * same way every other BOSS state file is, so the on-disk shape is read with
 * `ignoreUnknownKeys = true` and every field except [id] carries a default: a snippet written by
 * a newer build that adds a field still decodes on an older one, and a hand-written entry that
 * omits an optional field is accepted rather than dropped.
 */
@Serializable
data class Snippet(
    val id: String,
    val title: String = "",
    val body: String = "",
    val tags: List<String> = emptyList(),
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)

/**
 * The on-disk document: the whole snippet set under one key, mirroring
 * `RunConfigurationSettings`. A wrapper object rather than a bare list so a later field (a schema
 * version, an ordering preference) can be added without rewriting every existing file.
 */
@Serializable
data class SnippetLibrary(
    val snippets: List<Snippet> = emptyList(),
)
