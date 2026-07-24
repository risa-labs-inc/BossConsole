package ai.rever.boss.plugin.dependency

/**
 * A semantic version (https://semver.org). The sole version-comparison
 * primitive for plugin update/floor checks.
 *
 * Build metadata (`+...`) is intentionally NOT a field: SemVer §10 says it is
 * ignored for precedence, and no consumer reads it. [parse] accepts and
 * discards it so `"1.0.0+build.7"` parses, but two versions differing only in
 * build metadata are equal — keeping `equals`/`hashCode` consistent with
 * [compareTo] (a `data class` field that `compareTo` ignored would violate the
 * `Comparable` contract).
 */
data class SemanticVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val prerelease: String? = null,
) : Comparable<SemanticVersion> {
    companion object {
        /**
         * Parse a version string, or null if invalid.
         *
         * Accepts 1–3 numeric core parts (missing minor/patch default to 0),
         * an optional `-prerelease`, and optional `+build` metadata (discarded).
         * Rejects empty/non-numeric core parts (`"1..2"`, `"1.x"`), negative
         * numbers, and empty prerelease/build sections (`"1.0.0-"`, `"1.0.0+"`).
         */
        fun parse(version: String): SemanticVersion? {
            val trimmed = version.trim()
            if (trimmed.isEmpty()) return null

            // Strip build metadata (ignored per SemVer §10); a trailing '+' is invalid.
            val plus = trimmed.indexOf('+')
            val withoutBuild =
                when {
                    plus < 0 -> trimmed
                    plus == trimmed.length - 1 -> return null
                    else -> trimmed.substring(0, plus)
                }

            // Split off prerelease; a trailing '-' or empty identifier is invalid.
            val dash = withoutBuild.indexOf('-')
            val corePart: String
            val prerelease: String?
            if (dash < 0) {
                corePart = withoutBuild
                prerelease = null
            } else {
                if (dash == withoutBuild.length - 1) return null
                corePart = withoutBuild.substring(0, dash)
                prerelease = withoutBuild.substring(dash + 1)
                if (prerelease.split('.').any { it.isEmpty() }) return null
            }

            val parts = corePart.split(".")
            if (parts.size !in 1..3) return null
            // Every present core part must be a non-negative integer.
            val nums = parts.map { (it.toIntOrNull() ?: return null).also { n -> if (n < 0) return null } }

            return SemanticVersion(
                major = nums[0],
                minor = nums.getOrElse(1) { 0 },
                patch = nums.getOrElse(2) { 0 },
                prerelease = prerelease,
            )
        }

        /**
         * Compare prerelease sections per SemVer §11: dot-separated identifiers
         * compared left to right; numeric identifiers rank below by number,
         * alphanumeric by ASCII; numeric always ranks lower than alphanumeric;
         * a larger set of identifiers wins when all preceding are equal. A null
         * (absent) prerelease has HIGHER precedence than any prerelease.
         */
        private fun comparePrerelease(
            a: String?,
            b: String?,
        ): Int {
            if (a == null && b == null) return 0
            if (a == null) return 1
            if (b == null) return -1
            val ai = a.split('.')
            val bi = b.split('.')
            for (i in 0 until minOf(ai.size, bi.size)) {
                val x = ai[i]
                val y = bi[i]
                val xn = x.toIntOrNull()
                val yn = y.toIntOrNull()
                val c =
                    when {
                        xn != null && yn != null -> xn.compareTo(yn)

                        xn != null -> -1

                        // numeric < alphanumeric
                        yn != null -> 1

                        else -> x.compareTo(y)
                    }
                if (c != 0) return c
            }
            return ai.size.compareTo(bi.size)
        }
    }

    override fun compareTo(other: SemanticVersion): Int {
        if (major != other.major) return major.compareTo(other.major)
        if (minor != other.minor) return minor.compareTo(other.minor)
        if (patch != other.patch) return patch.compareTo(other.patch)
        return comparePrerelease(prerelease, other.prerelease)
    }

    override fun toString(): String =
        buildString {
            append("$major.$minor.$patch")
            prerelease?.let { append("-$it") }
        }
}
