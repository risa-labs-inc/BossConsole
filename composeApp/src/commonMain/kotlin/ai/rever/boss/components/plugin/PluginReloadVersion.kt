package ai.rever.boss.components.plugin

/** Version precedence for reload selection, including arbitrary-length prerelease identifiers. */
internal class PluginReloadVersion private constructor(
    private val core: List<String>,
    private val prerelease: List<String>,
) : Comparable<PluginReloadVersion> {
    override fun compareTo(other: PluginReloadVersion): Int {
        val coreOrder = compareIdentifiers(core, other.core)
        return when {
            coreOrder != 0 -> coreOrder
            prerelease.isEmpty() && other.prerelease.isNotEmpty() -> 1
            prerelease.isNotEmpty() && other.prerelease.isEmpty() -> -1
            else -> compareIdentifiers(prerelease, other.prerelease)
        }
    }

    companion object {
        private val pattern =
            Regex(
                """^(\d+\.\d+\.\d+)(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?""" +
                    """(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$""",
            )

        fun parse(value: String): PluginReloadVersion? {
            val match = pattern.matchEntire(value) ?: return null
            return PluginReloadVersion(
                core = match.groupValues[1].split('.'),
                prerelease = match.groupValues[2].takeIf { it.isNotEmpty() }?.split('.') ?: emptyList(),
            )
        }

        private fun compareIdentifiers(
            left: List<String>,
            right: List<String>,
        ): Int {
            for (index in 0 until minOf(left.size, right.size)) {
                val order = compareIdentifier(left[index], right[index])
                if (order != 0) return order
            }
            return left.size.compareTo(right.size)
        }

        private fun compareIdentifier(
            left: String,
            right: String,
        ): Int {
            val leftNumeric = left.all { it in '0'..'9' }
            val rightNumeric = right.all { it in '0'..'9' }
            return when {
                leftNumeric && rightNumeric -> {
                    // Compare by digit count then lexically, avoiding integer overflow.
                    val leftDigits = left.trimStart('0')
                    val rightDigits = right.trimStart('0')
                    compareValuesBy(leftDigits, rightDigits, { it.length }, { it })
                }

                leftNumeric -> {
                    -1
                }

                rightNumeric -> {
                    1
                }

                else -> {
                    left.compareTo(right)
                }
            }
        }
    }
}
