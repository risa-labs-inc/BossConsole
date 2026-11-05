package ai.rever.boss.plugin.launchpad.scan

/**
 * Makes text from inside a JAR safe to print.
 *
 * Every name in a report (a class, a plugin id, a URL host, a file name) was chosen by whoever built the JAR, and
 * the report is read in a terminal or by an agent. A line break in a plugin id would start a line the scan did not
 * write, and a terminal escape would act on the reader's screen, so both are shown as visible escapes instead of
 * being passed through. Nothing is dropped.
 */
internal object ScanText {
    private const val HEX_RADIX = 16
    private const val HEX_WIDTH = 4
    private const val SPACE = 0x20
    private const val DEL = 0x7f
    private const val C1_END = 0x9f
    private const val DEFAULT_MAX = 200

    private val invisible: Set<Char> =
        buildSet {
            add(0x061c.toChar())
            for (code in 0x200b..0x200f) add(code.toChar())
            for (code in 0x2028..0x202e) add(code.toChar())
            for (code in 0x2060..0x2064) add(code.toChar())
            for (code in 0x2066..0x2069) add(code.toChar())
            add(0xfeff.toChar())
        }

    /** [text] with every non-printing character shown as `\n`, `\t` or `\uXXXX`, cut to [max] characters. */
    fun safe(
        text: String,
        max: Int = DEFAULT_MAX,
    ): String {
        val out = StringBuilder()
        for ((shown, c) in text.withIndex()) {
            if (shown >= max) {
                out.append("...")
                break
            }
            when {
                c == '\n' -> {
                    out.append("\\n")
                }

                c == '\r' -> {
                    out.append("\\r")
                }

                c == '\t' -> {
                    out.append("\\t")
                }

                c.code < SPACE || c.code == DEL || c.code in 0x80..C1_END || c in invisible -> {
                    out.append("\\u").append(c.code.toString(HEX_RADIX).padStart(HEX_WIDTH, '0'))
                }

                else -> {
                    out.append(c)
                }
            }
        }
        return out.toString()
    }
}
