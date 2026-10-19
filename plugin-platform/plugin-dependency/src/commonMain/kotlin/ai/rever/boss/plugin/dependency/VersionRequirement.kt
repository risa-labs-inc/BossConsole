package ai.rever.boss.plugin.dependency

/** The comparison ranges supported by PluginDependency: exact, *, and whitespace-separated bounds. */
class VersionRequirement private constructor(
    private val bounds: List<Pair<String, SemanticVersion>>,
) {
    fun matches(version: String): Boolean {
        if (bounds.isEmpty()) return true
        val installed = SemanticVersion.parse(version.trim().removePrefix("v"))
        return installed != null &&
            bounds.all { (operator, required) ->
                when (operator) {
                    ">=" -> installed >= required
                    ">" -> installed > required
                    "<=" -> installed <= required
                    "<" -> installed < required
                    else -> installed == required
                }
            }
    }

    companion object {
        private val comparison =
            Regex(
                "(>=|<=|>|<|=)?(v?[0-9]+(?:\\.[0-9]+){0,2}" +
                    "(?:-[0-9A-Za-z.-]+)?(?:\\+[0-9A-Za-z.-]+)?)",
            )
        private val operatorSpace = Regex("(>=|<=|>|<|=)\\s+")

        /** Unknown or malformed constraints are rejected rather than treated as unrestricted. */
        fun parse(requirement: String): VersionRequirement? {
            val text = requirement.trim()
            if (text.isEmpty() || text == "*") return VersionRequirement(emptyList())
            val bounds = operatorSpace.replace(text, "$1").split(Regex("\\s+")).map(::parseBound)
            return if (bounds.any { it == null }) null else VersionRequirement(bounds.filterNotNull())
        }

        private fun parseBound(token: String): Pair<String, SemanticVersion>? {
            val match = comparison.matchEntire(token) ?: return null
            val version = SemanticVersion.parse(match.groupValues[2].removePrefix("v"))
            return version?.let { match.groupValues[1].ifEmpty { "=" } to it }
        }
    }
}
