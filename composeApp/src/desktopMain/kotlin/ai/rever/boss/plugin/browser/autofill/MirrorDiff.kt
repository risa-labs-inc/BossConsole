package ai.rever.boss.plugin.browser.autofill

/**
 * One reconcile step between what Secret Manager wants in Chromium and what is there.
 *
 * @param desired SM rows mapped to items, by key.
 * @param present keys Chromium holds right now (ours and the user's own).
 * @param owned keys the mirror added and still owns (the persisted index).
 * @param fingerprints in-memory value fingerprints of what the mirror last wrote, by key.
 */
internal data class MirrorPlan(
    val remove: Set<String>,
    val write: List<MirrorItem>,
    val skippedForeign: Set<String>,
)

internal object MirrorDiff {
    fun plan(
        desired: Map<String, MirrorItem>,
        present: Set<String>,
        owned: Set<String>,
        fingerprints: Map<String, String>,
    ): MirrorPlan {
        val remove = owned - desired.keys
        val write = mutableListOf<MirrorItem>()
        val foreign = mutableSetOf<String>()
        for ((key, item) in desired) {
            when {
                // A record the user (or Chromium itself) saved before the mirror knew it: never ours to touch.
                key in present && key !in owned -> foreign += key
                key in owned && key in present && fingerprints[key] == item.fingerprint -> Unit
                else -> write += item
            }
        }
        return MirrorPlan(remove = remove, write = write, skippedForeign = foreign)
    }
}
