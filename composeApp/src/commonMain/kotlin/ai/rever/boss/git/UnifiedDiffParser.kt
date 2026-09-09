package ai.rever.boss.git

import ai.rever.boss.plugin.api.DiffHunk
import ai.rever.boss.plugin.api.DiffLine
import ai.rever.boss.plugin.api.DiffLineKind
import ai.rever.boss.plugin.api.GitDiffData

/**
 * Parses `git diff` unified output (one or more files per stream) into [GitDiffData]
 * values.
 *
 * Handles: multiple `diff --git` sections, `rename from`/`rename to`,
 * `Binary files ... differ`, `@@` headers with optional line counts
 * (single-line hunks, and `0,0` for created/removed files), the
 * `\ No newline at end of file` marker, and blank context lines.
 *
 * Pure text in, structured data out - no git, no filesystem - so it is unit
 * tested against captured fixtures in desktopTest.
 */
object UnifiedDiffParser {
    /**
     * Parse a full unified diff stream. Files come back in input order; a file
     * section without hunks (e.g. mode-only changes) still appears with empty
     * hunks.
     */
    fun parse(input: String): List<GitDiffData> {
        // A process's diff ends with "\n", and lineSequence() turns that trailing
        // terminator into a final "" element. The hunk body accepts "" as a context
        // line, so that artefact was appended to the last hunk of every real diff -
        // one phantom blank line numbered past EOF. Fixtures use trimIndent() (no
        // trailing newline), which is why no test saw it.
        val rawLines =
            input.lineSequence().toList().let {
                if (input.endsWith("\n") && it.isNotEmpty() && it.last().isEmpty()) it.dropLast(1) else it
            }
        val files = mutableListOf<FileState>()
        var current: FileState? = null
        var hunk: HunkBuilder? = null

        fun closeHunk() {
            val h = hunk ?: return
            hunk = null
            current?.addHunk(h.build())
        }

        // endExclusive is where THIS file's section stops. Without it every
        // file's rawUnified ran to the end of the stream, so in a 3-file commit
        // diff files[0].rawUnified was the entire 3-file patch.
        fun closeFile(endExclusive: Int) {
            closeHunk()
            val f = current ?: return
            current = null
            f.rawEnd = endExclusive
            files.add(f)
        }

        for (i in rawLines.indices) {
            val line = rawLines[i]
            if (line.startsWith("diff --git ")) {
                closeFile(i)
                current = FileState(line, rawStart = i)
                continue
            }
            val cur = current ?: continue
            when {
                line.startsWith("rename from ") -> {
                    // Quoted by git under the same rules as the header path.
                    cur.oldPath = cUnquote(line.removePrefix("rename from "))
                }

                line.startsWith("rename to ") -> {
                    cur.path = cUnquote(line.removePrefix("rename to "))
                }

                line.startsWith("Binary files ") -> {
                    cur.isBinary = true
                }

                line.startsWith("@@") -> {
                    closeHunk()
                    // `git show <merge>` emits a COMBINED diff: "@@@ -a,b -c,d +e,f @@@"
                    // with two-column "++"/"+ " prefixes. Feeding that to HunkBuilder
                    // stripped one "@@", parsed "@ -a,b" as (1,1) and misclassified every
                    // body line - wrong line numbers, wrong add/remove, no error. There is
                    // no correct unified reading of it, so the file is reported with NO
                    // parsed hunks and its rawUnified intact, which is what that field is
                    // for. hunk stays null, so the body lines below are ignored.
                    hunk = if (line.startsWith("@@@")) null else HunkBuilder(line)
                }

                line.startsWith("\\") -> {
                    // "\ No newline at end of file": a marker, not a diff line.
                    continue
                }

                else -> {
                    val h = hunk
                    if (h != null && (line.startsWith("+") || line.startsWith("-") || line.startsWith(" ") || line.isEmpty())) {
                        h.addLine(line)
                    } else {
                        // File-level metadata (index/new file mode/old mode/similarity)
                        // or an unknown line: it ends any open hunk.
                        closeHunk()
                    }
                }
            }
        }
        closeFile(rawLines.size)
        return files.map { it.build(rawLines) }
    }

    private class FileState(
        val headerLine: String,
        val rawStart: Int,
    ) {
        var rawEnd: Int = rawStart
        var path: String = pathFromHeader(headerLine, newSide = true)
        var oldPath: String? =
            pathFromHeader(headerLine, newSide = false).let { if (it == path) null else it }
        var isBinary = false
        var additions = 0
        var deletions = 0
        val hunks = mutableListOf<DiffHunk>()

        init {
            require(path.isNotEmpty()) { "Could not read a path from: $headerLine" }
        }

        fun addHunk(hunk: DiffHunk) {
            additions += hunk.lines.count { it.kind == DiffLineKind.ADDED }
            deletions += hunk.lines.count { it.kind == DiffLineKind.REMOVED }
            hunks.add(hunk)
        }

        fun build(rawLines: List<String>): GitDiffData =
            GitDiffData(
                path = path,
                oldPath = oldPath,
                additions = additions,
                deletions = deletions,
                isBinary = isBinary,
                hunks = hunks,
                rawUnified = rawLines.subList(rawStart, rawEnd.coerceAtLeast(rawStart)).joinToString("\n"),
            )
    }

    private class HunkBuilder(
        header: String,
    ) {
        val header: String = header
        val oldStart: Int
        val oldLines: Int
        val newStart: Int
        val newLines: Int

        private var oldLineNo: Int
        private var newLineNo: Int
        private val lines = mutableListOf<DiffLine>()

        init {
            val body = header.removePrefix("@@").trimEnd('@').trim()
            // body looks like: "-0,0 +1,5" or "-12 +12,3" or "-12 +12"
            val parts = body.split(" ").filter { it.isNotEmpty() }
            val (os, oc) = parseSide(parts.firstOrNull().orEmpty())
            val (ns, nc) = parseSide(parts.getOrNull(1).orEmpty())
            oldStart = os
            oldLines = oc
            newStart = ns
            newLines = nc
            // A side that starts at 0 has no lines on that side to number.
            oldLineNo = os
            newLineNo = ns
        }

        fun addLine(line: String) {
            when (line.firstOrNull()) {
                '+' -> {
                    val n = newLineNo
                    newLineNo++
                    lines.add(
                        DiffLine(line.substring(1), DiffLineKind.ADDED, oldLine = null, newLine = n.takeIf { n > 0 }),
                    )
                }

                '-' -> {
                    val o = oldLineNo
                    oldLineNo++
                    lines.add(
                        DiffLine(line.substring(1), DiffLineKind.REMOVED, oldLine = o.takeIf { o > 0 }, newLine = null),
                    )
                }

                else -> {
                    // Context line (leading space, or a blank line).
                    val text = if (line.length > 1) line.substring(1) else ""
                    val o = oldLineNo.also { oldLineNo++ }
                    val n = newLineNo.also { newLineNo++ }
                    lines.add(
                        DiffLine(
                            text,
                            DiffLineKind.CONTEXT,
                            oldLine = o.takeIf { o > 0 },
                            newLine = n.takeIf { n > 0 },
                        ),
                    )
                }
            }
        }

        fun build(): DiffHunk =
            DiffHunk(
                oldStart = oldStart,
                oldLines = oldLines,
                newStart = newStart,
                newLines = newLines,
                header = header,
                lines = lines,
            )

        private fun parseSide(side: String): Pair<Int, Int> {
            // Drop the side's sign marker: "-12,3" / "+12".
            val unsigned = side.removePrefix("+").removePrefix("-")
            val idx = unsigned.indexOf(',')
            return if (idx < 0) {
                (unsigned.toIntOrNull() ?: 1) to 1
            } else {
                (unsigned.substring(0, idx).toIntOrNull() ?: 1) to (unsigned.substring(idx + 1).toIntOrNull() ?: 0)
            }
        }
    }

    /**
     * The old- or new-side path from a `diff --git` header.
     *
     * git quotes a path containing non-ASCII or special characters and escapes it
     * C-style, emitting UTF-8 as OCTAL BYTES: `diff --git "a/caf\303\251.txt"
     * "b/caf\303\251.txt"`. That header contains no ` b/`, so the previous
     * `lastIndexOf(" b/")` returned -1, the new-side path came back empty, and
     * FileState's `require(path.isNotEmpty())` threw - one accented or CJK filename
     * anywhere in a commit aborted the whole diff, uncaught, out through
     * GitDataProvider into the calling plugin.
     *
     * Each side is quoted independently, so both mixed forms are handled.
     */
    private fun pathFromHeader(
        header: String,
        newSide: Boolean,
    ): String {
        val after = header.removePrefix("diff --git ")
        val (a, b) = splitSides(after) ?: return ""
        val raw = if (newSide) b else a
        val prefix = if (newSide) "b/" else "a/"
        return cUnquote(raw).removePrefix(prefix)
    }

    /**
     * Splits `a/<old> b/<new>` into its two tokens, either of which may be
     * quoted.
     *
     * Known limit, deliberately left: the unquoted fallback is
     * `lastIndexOf(" b/")`, and git does not quote a path merely for
     * containing a space - so a directory literally named `b`
     * (`diff --git a/x b/y b/x b/y`) splits at the wrong point, with no
     * exception. It cannot be settled from the header alone; the
     * `--- a/` / `+++ b/` lines just below the header would settle it, and
     * anyone who needs certainty can parse `git diff --raw -z` instead.
     */
    private fun splitSides(s: String): Pair<String, String>? {
        val t = s.trim()
        if (t.startsWith("\"")) {
            val end = closingQuote(t) ?: return null
            val rest = t.substring(end + 1).trim()
            if (rest.isEmpty()) return null
            return t.substring(0, end + 1) to rest
        }
        // Unquoted old side: the new side is either ` b/…` or ` "b/…"`.
        val q = t.indexOf(" \"")
        if (q >= 0) return t.substring(0, q) to t.substring(q + 1)
        val idx = t.lastIndexOf(" b/")
        if (idx < 0) return null
        return t.substring(0, idx) to t.substring(idx + 1)
    }

    /** Index of the quote closing the one at position 0, honouring backslash escapes. */
    private fun closingQuote(s: String): Int? {
        var i = 1
        while (i < s.length) {
            when (s[i]) {
                '\\' -> i++
                '"' -> return i
            }
            i++
        }
        return null
    }

    /**
     * Undoes git's C-style quoting, decoding `\ooo` escapes as UTF-8 BYTES.
     *
     * Byte-wise, not char-wise: `\303\251` is the two-byte UTF-8 encoding of a
     * single `é`. Mapping each octal escape to its own Char would produce `Ã©`.
     * An unquoted token is returned unchanged.
     */
    internal fun cUnquote(p: String): String {
        val t = p.trim()
        if (!t.startsWith("\"") || !t.endsWith("\"") || t.length < 2) return t
        val body = t.substring(1, t.length - 1)
        val bytes = ArrayList<Byte>(body.length)
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (c != '\\' || i == body.length - 1) {
                for (b in c.toString().encodeToByteArray()) bytes.add(b)
                i++
                continue
            }
            val n = body[i + 1]
            when (n) {
                'n' -> {
                    bytes.add('\n'.code.toByte())
                    i += 2
                }

                't' -> {
                    bytes.add('\t'.code.toByte())
                    i += 2
                }

                'r' -> {
                    bytes.add('\r'.code.toByte())
                    i += 2
                }

                '"' -> {
                    bytes.add('"'.code.toByte())
                    i += 2
                }

                '\\' -> {
                    bytes.add('\\'.code.toByte())
                    i += 2
                }

                in '0'..'7' -> {
                    var len = 0
                    var v = 0
                    while (len < 3 && i + 1 + len < body.length && body[i + 1 + len] in '0'..'7') {
                        v = v * 8 + (body[i + 1 + len] - '0')
                        len++
                    }
                    bytes.add((v and 0xFF).toByte())
                    i += 1 + len
                }

                else -> {
                    for (b in n.toString().encodeToByteArray()) bytes.add(b)
                    i += 2
                }
            }
        }
        return bytes.toByteArray().decodeToString()
    }
}
