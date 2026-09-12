package ai.rever.boss.kernel

/** Snapshot before parent termination: an exiting parent can reparent its descendants. */
internal fun processDescendants(process: Process?): List<ProcessHandle> =
    runCatching {
        process
            ?.toHandle()
            ?.descendants()
            ?.use { it.toList() }
            .orEmpty()
    }.getOrDefault(emptyList())

/** Called after the parent's graceful wait, or immediately on a forced cleanup path. */
internal fun killProcessDescendants(descendants: List<ProcessHandle>) {
    descendants.forEach { descendant ->
        runCatching { if (descendant.isAlive) descendant.destroyForcibly() }
    }
}
