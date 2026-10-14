package ai.rever.boss.search

/** Filename navigation can use bounded results; malformed ignore rules still fail closed. */
internal fun filenameIndexFrom(result: ProjectDiscoveryResult): List<IndexedFile> {
    if (result.incompleteReason != null && !result.budgetExceeded) {
        throw ProjectDiscoveryIncompleteException(result.incompleteReason)
    }
    return result.files
        .map { IndexedFile(it.file.name, it.file.absolutePath, it.relativePath) }
        .sortedBy { it.lowerName }
}
