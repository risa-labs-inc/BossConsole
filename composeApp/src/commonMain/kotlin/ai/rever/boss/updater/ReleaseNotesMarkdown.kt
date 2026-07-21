package ai.rever.boss.updater

import ai.rever.boss.plugin.ui.BossDarkAccent
import ai.rever.boss.plugin.ui.BossDarkBorder
import ai.rever.boss.plugin.ui.BossDarkSurface
import ai.rever.boss.plugin.ui.BossDarkTextMuted
import ai.rever.boss.plugin.ui.BossDarkTextPrimary
import ai.rever.boss.plugin.ui.BossDarkTextSecondary
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Divider
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Minimal markdown support for the release notes shown in [UpdateAvailableDialog],
 * covering the subset our generated release notes actually use: headings, bullet /
 * numbered lists, GFM tables, fenced code blocks, thematic breaks, and inline
 * bold / italic / code / strikethrough / links.
 *
 * This intentionally does NOT reuse the editor-tab browser-based preview
 * (marked.js inside JxBrowser): heavyweight browser views can't be layered
 * inside a Compose dialog popup, so the notes are rendered natively instead.
 * Callers must treat parsing as best-effort and fall back to plain text if
 * [parseReleaseNotes] fails or produces nothing.
 */
internal sealed interface NotesBlock {
    data class Heading(val level: Int, val text: String) : NotesBlock
    data class Paragraph(val text: String) : NotesBlock
    data class ListItem(val marker: String, val text: String, val indent: Int) : NotesBlock
    data class CodeBlock(val code: String) : NotesBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : NotesBlock
    data object ThematicBreak : NotesBlock
}

private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
private val BULLET = Regex("^([-*+])\\s+(.*)$")
private val ORDERED = Regex("^(\\d{1,3})[.)]\\s+(.*)$")
private val THEMATIC_BREAK = Regex("^(-{3,}|\\*{3,}|_{3,})$")

internal fun parseReleaseNotes(source: String): List<NotesBlock> {
    val blocks = mutableListOf<NotesBlock>()
    val paragraph = StringBuilder()

    fun flushParagraph() {
        if (paragraph.isNotBlank()) blocks += NotesBlock.Paragraph(paragraph.toString().trim())
        paragraph.clear()
    }

    val lines = source.lines()
    var i = 0
    var inFence = false
    val fence = StringBuilder()
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()

        if (inFence) {
            if (trimmed.startsWith("```")) {
                blocks += NotesBlock.CodeBlock(fence.toString().trimEnd('\n'))
                fence.clear()
                inFence = false
            } else {
                fence.append(line).append('\n')
            }
            i++
            continue
        }

        val heading = HEADING.matchEntire(trimmed)
        val bullet = BULLET.matchEntire(trimmed)
        val ordered = ORDERED.matchEntire(trimmed)
        when {
            trimmed.isEmpty() -> flushParagraph()

            trimmed.startsWith("```") -> {
                flushParagraph()
                inFence = true
            }

            heading != null -> {
                flushParagraph()
                blocks += NotesBlock.Heading(heading.groupValues[1].length, heading.groupValues[2].trim())
            }

            isTableRow(trimmed) && i + 1 < lines.size && isTableSeparator(lines[i + 1].trim()) -> {
                flushParagraph()
                val header = splitTableRow(trimmed)
                val rows = mutableListOf<List<String>>()
                i += 2
                while (i < lines.size && isTableRow(lines[i].trim())) {
                    rows += splitTableRow(lines[i].trim())
                    i++
                }
                blocks += NotesBlock.Table(header, rows)
                continue
            }

            THEMATIC_BREAK.matches(trimmed) -> {
                flushParagraph()
                blocks += NotesBlock.ThematicBreak
            }

            bullet != null -> {
                flushParagraph()
                blocks += NotesBlock.ListItem("•", bullet.groupValues[2], indentLevel(line))
            }

            ordered != null -> {
                flushParagraph()
                blocks += NotesBlock.ListItem("${ordered.groupValues[1]}.", ordered.groupValues[2], indentLevel(line))
            }

            else -> paragraph.append(trimmed).append(' ')
        }
        i++
    }
    if (inFence && fence.isNotBlank()) blocks += NotesBlock.CodeBlock(fence.toString().trimEnd('\n'))
    flushParagraph()
    return blocks
}

private fun indentLevel(line: String): Int =
    ((line.length - line.trimStart().length) / 2).coerceIn(0, 3)

private fun isTableRow(trimmed: String): Boolean =
    trimmed.startsWith("|") && trimmed.length > 1

private fun isTableSeparator(trimmed: String): Boolean =
    trimmed.contains('-') && trimmed.contains('|') && trimmed.all { it in "|-: \t" }

private fun splitTableRow(trimmed: String): List<String> =
    trimmed.removePrefix("|").removeSuffix("|").split('|').map { it.trim() }

// Alternatives ordered so that at any position the longest-delimiter form wins
// (e.g. ** matches as bold before * can match as italic). `\b_..._\b` keeps
// snake_case identifiers from reading as italics (`_` is a word character).
private val INLINE = Regex(
    "(`[^`]+`)" +
        "|(\\*\\*[^*]+\\*\\*)|(__[^_]+__)" +
        "|(~~[^~]+~~)" +
        "|(\\[[^\\]]+\\]\\([^)\\s]+\\))" +
        "|(\\*[^*\\s][^*]*\\*)|(\\b_[^_\\s][^_]*_\\b)"
)
private val LINK = Regex("^\\[([^\\]]+)\\]\\(([^)\\s]+)\\)$")

/** Renders one markdown span run into an [AnnotatedString] (no block nesting). */
internal fun buildInlineMarkdown(text: String): AnnotatedString = buildAnnotatedString {
    val codeStyle = SpanStyle(
        fontFamily = FontFamily.Monospace,
        color = BossDarkTextPrimary,
        background = BossDarkSurface,
        fontSize = 11.sp
    )
    var pos = 0
    for (match in INLINE.findAll(text)) {
        append(text.substring(pos, match.range.first))
        val token = match.value
        when {
            token.startsWith("`") ->
                withStyle(codeStyle) { append(token.removeSurrounding("`")) }
            token.startsWith("**") ->
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(token.removeSurrounding("**")) }
            token.startsWith("__") ->
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(token.removeSurrounding("__")) }
            token.startsWith("~~") ->
                withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(token.removeSurrounding("~~")) }
            token.startsWith("[") -> {
                val link = LINK.matchEntire(token)
                if (link != null) {
                    val styles = TextLinkStyles(
                        style = SpanStyle(color = BossDarkAccent, textDecoration = TextDecoration.Underline)
                    )
                    withLink(LinkAnnotation.Url(link.groupValues[2], styles)) { append(link.groupValues[1]) }
                } else {
                    append(token)
                }
            }
            else ->
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(token.substring(1, token.length - 1)) }
        }
        pos = match.range.last + 1
    }
    append(text.substring(pos))
}

private fun headingFontSize(level: Int) = when (level) {
    1 -> 16.sp
    2 -> 14.sp
    else -> 13.sp
}

/** One block of rendered release notes; sized for the update dialog (12sp body). */
@Composable
internal fun NotesBlockView(block: NotesBlock) {
    when (block) {
        is NotesBlock.Heading -> Text(
            buildInlineMarkdown(block.text),
            color = BossDarkTextPrimary,
            fontSize = headingFontSize(block.level),
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 6.dp)
        )

        is NotesBlock.Paragraph -> Text(
            buildInlineMarkdown(block.text),
            color = BossDarkTextSecondary,
            fontSize = 12.sp,
            lineHeight = 17.sp
        )

        is NotesBlock.ListItem -> Row(Modifier.padding(start = (12 * block.indent).dp)) {
            Text(
                block.marker,
                color = BossDarkTextMuted,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                modifier = Modifier.width(18.dp)
            )
            Text(
                buildInlineMarkdown(block.text),
                color = BossDarkTextSecondary,
                fontSize = 12.sp,
                lineHeight = 17.sp
            )
        }

        is NotesBlock.CodeBlock -> Text(
            block.code,
            color = BossDarkTextPrimary,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            modifier = Modifier
                .fillMaxWidth()
                .background(BossDarkSurface, RoundedCornerShape(4.dp))
                .padding(8.dp)
        )

        is NotesBlock.Table -> Column(
            Modifier
                .fillMaxWidth()
                .background(BossDarkSurface.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
                .padding(vertical = 2.dp)
        ) {
            Row(Modifier.padding(horizontal = 6.dp, vertical = 3.dp)) {
                block.header.forEach { cell ->
                    Text(
                        buildInlineMarkdown(cell),
                        color = BossDarkTextPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f).padding(end = 6.dp)
                    )
                }
            }
            Divider(color = BossDarkBorder, thickness = 1.dp)
            block.rows.forEach { row ->
                Row(Modifier.padding(horizontal = 6.dp, vertical = 3.dp)) {
                    // Pad/trim ragged rows to the header width so weights stay aligned.
                    List(block.header.size) { idx -> row.getOrElse(idx) { "" } }.forEach { cell ->
                        Text(
                            buildInlineMarkdown(cell),
                            color = BossDarkTextSecondary,
                            fontSize = 11.sp,
                            modifier = Modifier.weight(1f).padding(end = 6.dp)
                        )
                    }
                }
            }
        }

        NotesBlock.ThematicBreak -> Divider(
            color = BossDarkBorder,
            thickness = 1.dp,
            modifier = Modifier.padding(vertical = 4.dp)
        )
    }
}
