package ai.rever.boss.plugin.launchpad.scan

/**
 * What changed between two builds of a plugin, in the terms that matter when deciding whether to load the newer
 * one: which capabilities it gained or lost, what new hosts its code names, and what permissions it now asks for.
 *
 * Capabilities are compared by id, not by evidence, so moving a call to another class is not a change. Signature
 * findings are left out: they describe the two files on disk, not the plugins.
 */
@Suppress("LongParameterList")
internal class ScanDiff(
    val old: ScanResult,
    val new: ScanResult,
    val addedCapabilities: List<CapabilityUse>,
    val removedCapabilities: List<CapabilityUse>,
    val addedHosts: List<String>,
    val removedHosts: List<String>,
    val addedFindings: List<ScanFinding>,
    val removedFindings: List<ScanFinding>,
    val addedPermissions: List<String>,
    val removedPermissions: List<String>,
) {
    val samePlugin: Boolean get() = old.pluginId != null && old.pluginId == new.pluginId

    /**
     * The highest risk among what was ADDED: what the newer build can do, or name, that the older could not.
     * A newly named host has no capability or finding of its own, but [verdict] and `--fail-on` must not
     * disagree about whether something changed - so a nonempty [addedHosts] floors this at LOW rather than
     * leaving it at INFO while the verdict text says a build "gained capabilities".
     */
    val addedRisk: ScanRisk
        get() {
            val risks = addedCapabilities.map { it.capability.risk } + addedFindings.map { it.risk }
            val hostFloor = if (addedHosts.isNotEmpty()) listOf(ScanRisk.LOW) else emptyList()
            return (risks + hostFloor).maxOrNull() ?: ScanRisk.INFO
        }

    private val gainedAnything: Boolean
        get() =
            addedCapabilities.isNotEmpty() ||
                addedHosts.isNotEmpty() ||
                addedFindings.any { it.risk >= ScanRisk.MEDIUM }

    val verdict: String
        get() =
            when {
                old.unreadableReason != null || new.unreadableReason != null -> {
                    "NOT COMPARED - one of the JARs could not be scanned"
                }

                addedRisk == ScanRisk.HIGH -> {
                    "REVIEW BEFORE LOADING - the newer build gained high-risk capabilities"
                }

                gainedAnything -> {
                    "REVIEW - the newer build gained capabilities"
                }

                else -> {
                    "NO NEW CAPABILITIES in the newer build (static scan; see the limits of each report)"
                }
            }

    companion object {
        fun of(
            old: ScanResult,
            new: ScanResult,
        ): ScanDiff {
            val oldCaps = old.capabilities.associateBy { it.capability.id }
            val newCaps = new.capabilities.associateBy { it.capability.id }
            val oldFindings = comparable(old).associateBy { it.id }
            val newFindings = comparable(new).associateBy { it.id }
            val oldPerms =
                old.manifest
                    ?.permissions
                    .orEmpty()
                    .toSet()
            val newPerms =
                new.manifest
                    ?.permissions
                    .orEmpty()
                    .toSet()
            return ScanDiff(
                old = old,
                new = new,
                addedCapabilities = newCaps.filterKeys { it !in oldCaps }.values.byRisk(),
                removedCapabilities = oldCaps.filterKeys { it !in newCaps }.values.byRisk(),
                addedHosts = (new.hosts - old.hosts.toSet()).sorted(),
                removedHosts = (old.hosts - new.hosts.toSet()).sorted(),
                addedFindings = newFindings.filterKeys { it !in oldFindings }.values.sortedByDescending { it.risk },
                removedFindings = oldFindings.filterKeys { it !in newFindings }.values.sortedByDescending { it.risk },
                addedPermissions = (newPerms - oldPerms).sorted(),
                removedPermissions = (oldPerms - newPerms).sorted(),
            )
        }

        private fun comparable(r: ScanResult) = r.findings.filterNot { it.id.startsWith("sig.") }

        private fun Collection<CapabilityUse>.byRisk() =
            sortedWith(compareByDescending<CapabilityUse> { it.capability.risk }.thenBy { it.capability.id })
    }
}
